package libcore

import (
	"fmt"
	"net"
	"net/netip"
	"strings"

	"github.com/oschwald/maxminddb-golang"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/json/badoption"
)

type geoip struct {
	geoipReader *maxminddb.Reader
}

func (g *geoip) Open(path string) error {
	geoipReader, err := maxminddb.Open(path)
	g.geoipReader = geoipReader
	return err
}

func (g *geoip) Rules(countryCode string) ([]option.HeadlessRule, error) {
	networks := g.geoipReader.Networks(maxminddb.SkipAliasedNetworks)
	countryMap := make(map[string][]*net.IPNet)
	var (
		ipNet           *net.IPNet
		nextCountryCode string
		err             error
	)
	for networks.Next() {
		ipNet, err = networks.Network(&nextCountryCode)
		if err != nil {
			return nil, fmt.Errorf("failed to get network: %w", err)
		}
		countryMap[nextCountryCode] = append(countryMap[nextCountryCode], ipNet)
	}

	ipNets := countryMap[strings.ToLower(countryCode)]

	if len(ipNets) == 0 {
		return nil, fmt.Errorf("no networks found for country code: %s", countryCode)
	}

	var headlessRule option.DefaultHeadlessRule
	headlessRule.IPCIDR = make([]*badoption.Prefixable, 0, len(ipNets))
	for _, cidr := range ipNets {
		if prefix, err := netip.ParsePrefix(cidr.String()); err == nil {
			headlessRule.IPCIDR = append(headlessRule.IPCIDR, common.Ptr(badoption.Prefixable(prefix)))
		}
	}

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}

// loadGeoIPRules 从 geoip.db 读取指定国家代码的规则
// （替代 fork 的 nekoutils.GetGeoIPHeadlessRules 钩子）。
func loadGeoIPRules(dbPath string, code string) ([]option.HeadlessRule, error) {
	g := new(geoip)
	if err := g.Open(dbPath); err != nil {
		return nil, err
	}
	defer g.geoipReader.Close()
	return g.Rules(code)
}
