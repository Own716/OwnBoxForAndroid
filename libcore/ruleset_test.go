package libcore

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

func TestPrepareRemoteRuleSetsIgnoresEmptyPlaceholderAndPrefersPopulatedCache(t *testing.T) {
	tmpDir := t.TempDir()
	oldAssets := externalAssetsPath
	externalAssetsPath = tmpDir
	t.Cleanup(func() { externalAssetsPath = oldAssets })

	srsDir := filepath.Join(tmpDir, "srs")
	if err := os.MkdirAll(srsDir, 0755); err != nil {
		t.Fatal(err)
	}

	// tag="geoip:cn": altPath="geoip-cn.srs", dstPath="geoip_cn.srs"
	altPlaceholder := filepath.Join(srsDir, "geoip-cn.srs")
	f, err := os.Create(altPlaceholder)
	if err != nil {
		t.Fatal(err)
	}
	if err := srs.Write(f, option.PlainRuleSet{}, C.RuleSetVersionCurrent); err != nil {
		_ = f.Close()
		t.Fatal(err)
	}
	_ = f.Close()

	if isPopulatedSRSFile(altPlaceholder) {
		t.Fatalf("expected empty SRS placeholder %s not to be treated as populated", altPlaceholder)
	}

	populatedDst := filepath.Join(srsDir, "geoip_cn.srs")
	f2, err := os.Create(populatedDst)
	if err != nil {
		t.Fatal(err)
	}
	nonEmpty := option.PlainRuleSet{
		Rules: []option.HeadlessRule{
			{
				Type: C.RuleTypeDefault,
				DefaultOptions: option.DefaultHeadlessRule{
					DomainSuffix: []string{"example.cn"},
				},
			},
		},
	}
	if err := srs.Write(f2, nonEmpty, C.RuleSetVersionCurrent); err != nil {
		_ = f2.Close()
		t.Fatal(err)
	}
	_ = f2.Close()

	if !isPopulatedSRSFile(populatedDst) {
		t.Fatalf("expected populated SRS file %s to be recognized", populatedDst)
	}

	sets := []option.RuleSet{
		{
			Type: C.RuleSetTypeRemote,
			Tag:  []string{"geoip:cn"},
		},
	}
	if err := prepareRemoteRuleSets(sets); err != nil {
		t.Fatal(err)
	}
	if sets[0].RemoteOptions.InitialPath != populatedDst {
		t.Fatalf("expected InitialPath=%s, got %s", populatedDst, sets[0].RemoteOptions.InitialPath)
	}
}
