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
		code, isGeoIP, _, ok := parseGeoRuleSetPath(rs.LocalOptions.Path)
		if !ok {
			continue
		}
		var dbName string
		if isGeoIP {
			dbName = geoipDat
		} else {
			dbName = geositeDat
		}

		// 优先查找本地已存在的官方 .srs 文件（官方编译格式效率更高）
		officialSRS := filepath.Join(externalAssetsPath, fmt.Sprintf("%s-%s.srs", dbName[:len(dbName)-3], code))
		if info, err := os.Stat(officialSRS); err == nil && info.Size() > 64 {
			rs.LocalOptions.Path = officialSRS
			continue
		}

		tag := ""
		if len(rs.Tag) > 0 {
			tag = rs.Tag[0]
		}
		// 探测数据库实际存储路径（external 或 internal）
		actualDbPath := filepath.Join(externalAssetsPath, dbName)
		if _, err := os.Stat(actualDbPath); err != nil && internalAssetsPath != "" {
			altPath := filepath.Join(internalAssetsPath, dbName)
			if _, err := os.Stat(altPath); err == nil {
				actualDbPath = altPath
			}
		}
		dstPath, err := convertGeoRuleSetToSRS(tag, code, actualDbPath, isGeoIP)
		if err != nil {
			return fmt.Errorf("rule-set %v: %w", rs.Tag, err)
		}
		rs.LocalOptions.Path = dstPath
	}
	return nil
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

		info, err := os.Stat(dstPath)
		if err != nil || info.Size() == 0 {
			file, createErr := os.Create(dstPath)
			if createErr == nil {
				_ = srs.Write(file, option.PlainRuleSet{}, C.RuleSetVersionCurrent)
				_ = file.Close()
			}
		}
		rs.RemoteOptions.InitialPath = dstPath
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

	// 缓存复用：.srs 必须存在且有效（文件大小 > 64 字节，杜绝空 SRS 缓存中毒）
	if srsInfo, err := os.Stat(dst); err == nil && srsInfo.Size() > 64 {
		if dbInfo, err := os.Stat(dbPath); err == nil && srsInfo.ModTime().After(dbInfo.ModTime()) {
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

	// 针对 geosite:cn 的自愈兜底：若本地缺失 geosite.db，生成完整包含中国顶级域名的兜底规则
	if (err != nil || len(rules) == 0) && !isGeoIP && (strings.EqualFold(code, "cn") || strings.EqualFold(code, "china")) {
		log.Printf("Info: providing built-in CN domain suffix rules for geosite:%s", code)
		rules = []option.HeadlessRule{
			{
				Type: C.RuleTypeDefault,
				DefaultOptions: option.DefaultHeadlessRule{
					DomainSuffix: []string{
						"cn", "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
						"baidu.com", "qq.com", "tencent.com", "alibaba.com", "alipay.com",
						"taobao.com", "tmall.com", "jd.com", "bilibili.com", "163.com",
						"126.com", "sina.com.cn", "weibo.com", "zhihu.com", "douyin.com",
						"bytedance.com", "toutiao.com", "meituan.com", "kuaishou.com",
						"xiaomi.com", "huawei.com", "honor.com", "oppo.com", "vivo.com",
						"speedtest.cn",
					},
				},
			},
		}
		err = nil
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
