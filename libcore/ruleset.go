package libcore

// 官方内核的 local rule-set 只接受真实文件路径（.srs binary / .json source），
// 不认识 fork 私有的 "geoip:xxx" / "geosite:xxx" 伪路径。
//
// 本文件在 box.New 之前预处理配置中的 local rule-set：
//   - 官方格式（geoip-cn / geosite-cn，可带 .srs 后缀）：优先直接指向
//     <externalAssets>/geoip-cn.srs 等已存在的官方规则集文件；
//     文件不存在时回退到从本地 geoip.db / geosite.db 转换生成。
//   - 老 nb4a 格式（geoip:cn / geosite:cn）：兼容处理，从本地 db 转换生成 .srs。
//
// 生成的 .srs 缓存于 <externalAssets>/srs/，db 更新后自动重建。

import (
	"bufio"
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"

	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

// parseGeoRuleSetPath 识别 rule-set path 中的 geo 引用，
// 返回规则代码、是否 geoip、是否老 nb4a 格式；非 geo 引用返回 ok=false。
func parseGeoRuleSetPath(path string) (code string, isGeoIP bool, legacy bool, ok bool) {
	// 老 nb4a 格式：geoip:cn / geosite:cn
	if rest, found := strings.CutPrefix(path, "geoip:"); found {
		return rest, true, true, rest != ""
	}
	if rest, found := strings.CutPrefix(path, "geosite:"); found {
		return rest, false, true, rest != ""
	}
	// 官方格式：geoip-cn(.srs) / geosite-cn(.srs)
	name := strings.TrimSuffix(filepath.Base(path), ".srs")
	if rest, found := strings.CutPrefix(name, "geoip-"); found {
		return rest, true, false, rest != ""
	}
	if rest, found := strings.CutPrefix(name, "geosite-"); found {
		return rest, false, false, rest != ""
	}
	return "", false, false, false
}

func prepareLocalGeoRuleSets(ruleSets []option.RuleSet) error {
	for i := range ruleSets {
		rs := &ruleSets[i]
		if rs.Type != C.RuleSetTypeLocal {
			continue
		}
		code, isGeoIP, legacy, ok := parseGeoRuleSetPath(rs.LocalOptions.Path)
		if !ok {
			continue
		}
		var dbName string
		if isGeoIP {
			dbName = geoipDat
		} else {
			dbName = geositeDat
		}

		// 官方格式优先：已存在的官方 .srs 文件或 srs 缓存文件直接使用
		if !legacy {
			baseName := fmt.Sprintf("%s-%s.srs", dbName[:len(dbName)-3], code)
			officialPath := filepath.Join(externalAssetsPath, baseName)
			cachedSrsPath := filepath.Join(externalAssetsPath, "srs", baseName)
			if info, err := os.Stat(officialPath); err == nil && info.Size() > 0 {
				rs.LocalOptions.Path = officialPath
				continue
			}
			if info, err := os.Stat(cachedSrsPath); err == nil && info.Size() > 0 {
				rs.LocalOptions.Path = cachedSrsPath
				continue
			}
		}

		tag := ""
		if len(rs.Tag) > 0 {
			tag = rs.Tag[0]
		}
		dstPath, err := convertGeoRuleSetToSRS(tag, code, filepath.Join(externalAssetsPath, dbName), isGeoIP)
		if err != nil {
			return fmt.Errorf("rule-set %v: %w", rs.Tag, err)
		}
		rs.LocalOptions.Path = dstPath
	}
	return nil
}

// isPopulatedSRSFile 判断缓存规则集是否为有效非空文件（排除启动兜底生成的 0 规则空 SRS 占位）。
func isPopulatedSRSFile(path string) bool {
	info, err := os.Stat(path)
	if err != nil || info.IsDir() || info.Size() == 0 {
		return false
	}
	// 正常包含规则的 .srs/.json 文件远超 128 字节，直接放行避免启动遍历读盘
	if info.Size() > 128 {
		return true
	}
	data, err := os.ReadFile(path)
	if err != nil || len(data) == 0 {
		return false
	}
	if len(data) < 4 || !bytes.Equal(data[:3], srs.MagicBytes[:]) {
		// 非二进制 SRS（例如极简 JSON 规则源）只需非空
		return len(data) > 2
	}
	zr, err := zlib.NewReader(bytes.NewReader(data[4:]))
	if err != nil {
		return false
	}
	defer zr.Close()
	ruleCount, err := binary.ReadUvarint(bufio.NewReader(zr))
	return err == nil && ruleCount > 0
}

// prepareRemoteRuleSets 预处理远端 rule-set，配置本地 initial_path 兜底文件。
// 当首次启动或无缓存时，生成空 SRS 占位，避免 sing-box 在 box.Start 同步下载超时（context deadline exceeded），
// 确保核心在几毫秒内秒启并畅通 VPN，随后由内置 RuleSetUpdater 在后台异步平滑拉取并更新规则。
func prepareRemoteRuleSets(ruleSets []option.RuleSet) error {
	dir := filepath.Join(externalAssetsPath, "srs")
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}
	for i := range ruleSets {
		rs := &ruleSets[i]
		if rs.Type != C.RuleSetTypeRemote {
			continue
		}
		if rs.RemoteOptions.InitialPath != "" {
			if _, err := os.Stat(rs.RemoteOptions.InitialPath); err == nil {
				continue
			}
		}
		tag := ""
		if len(rs.Tag) > 0 {
			tag = rs.Tag[0]
		}
		safeTag := strings.NewReplacer(":", "_", "/", "_", "\\", "_", "?", "_", "&", "_", "=", "_").Replace(tag)
		dstPath := filepath.Join(dir, safeTag+".srs")

		// 兼顾 Kotlin 端 tagFor 标准命名（如 geoip-cn.srs 与 geoip_cn.srs 互通），优先复用真实已填充缓存
		altTag := strings.NewReplacer(":", "-", "/", "-", "\\", "-", "?", "-", "&", "-", "=", "-").Replace(tag)
		altPath := filepath.Join(dir, altTag+".srs")

		if isPopulatedSRSFile(altPath) {
			rs.RemoteOptions.InitialPath = altPath
			continue
		}
		if isPopulatedSRSFile(dstPath) {
			rs.RemoteOptions.InitialPath = dstPath
			continue
		}

		// 两者均无真实缓存时，保留或创建占位文件避免启动阻塞
		effectivePath := altPath
		if _, statErr := os.Stat(effectivePath); statErr != nil {
			file, createErr := os.Create(effectivePath)
			if createErr == nil {
				_ = srs.Write(file, option.PlainRuleSet{}, C.RuleSetVersionCurrent)
				_ = file.Close()
			}
		}
		rs.RemoteOptions.InitialPath = effectivePath
	}
	return nil
}

// convertGeoRuleSetToSRS 从 geoip.db/geosite.db 提取指定代码的规则并生成 .srs 缓存文件。
func convertGeoRuleSetToSRS(tag string, code string, dbPath string, isGeoIP bool) (string, error) {
	dir := filepath.Join(externalAssetsPath, "srs")
	if err := os.MkdirAll(dir, 0755); err != nil {
		return "", err
	}
	// tag 可能是 "geoip:cn" 等，含文件名不安全字符
	safeTag := strings.NewReplacer(":", "_", "/", "_", "\\", "_").Replace(tag)
	dst := filepath.Join(dir, safeTag+".srs")

	// 缓存复用：.srs 比 db 新则无需重建
	if dbInfo, err := os.Stat(dbPath); err == nil {
		if srsInfo, err := os.Stat(dst); err == nil && srsInfo.ModTime().After(dbInfo.ModTime()) {
			return dst, nil
		}
	}

	var rules []option.HeadlessRule
	var err error
	if isGeoIP {
		rules, err = loadGeoIPRules(dbPath, code)
	} else {
		rules, err = loadGeoSiteRules(dbPath, code)
	}
	if err != nil {
		log.Printf("Warning: failed to load %s rule code '%s' from %s: %v, writing empty SRS fallback", tag, code, dbPath, err)
		rules = []option.HeadlessRule{}
	}

	file, err := os.Create(dst)
	if err != nil {
		return "", err
	}
	defer file.Close()
	err = srs.Write(file, option.PlainRuleSet{Rules: rules}, C.RuleSetVersionCurrent)
	if err != nil {
		return "", err
	}
	return dst, nil
}
