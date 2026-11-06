package libcore

import (
	"testing"

	"libcore/protocol/juicity"
	"libcore/protocol/loadbalance"

	"github.com/sagernet/sing-box/option"
)

func TestSanitizeOutbounds(t *testing.T) {
	outbounds := []option.Outbound{
		{
			Type: "anytls",
			Options: &option.AnyTLSOutboundOptions{
				DialerOptions: option.DialerOptions{
					AbstractDialerOptions: option.AbstractDialerOptions{
						TCPFastOpen: true,
					},
				},
			},
		},
		{
			Type: "hysteria2",
			Options: &option.Hysteria2OutboundOptions{
				Obfs: &option.Hysteria2Obfs{
					Type:     "salamander",
					Password: "",
				},
			},
		},
		{
			Type: "tuic",
			Options: &option.TUICOutboundOptions{
				UDPOverStream: true,
				UDPRelayMode:  "native",
			},
		},
		{
			Type: "wireguard",
			Options: &option.WireGuardEndpointOptions{
				ListenPort: 12345,
				DialerOptions: option.DialerOptions{
					Detour: "some-tag",
				},
			},
		},
		{
			Type: "snell",
			Options: &option.SnellOutboundOptions{
				Version: 0,
			},
		},
		{
			Type: "naive",
			Options: &option.NaiveOutboundOptions{
				DialerOptions: option.DialerOptions{
					AbstractDialerOptions: option.AbstractDialerOptions{
						TCPFastOpen: true,
					},
				},
				OutboundTLSOptionsContainer: option.OutboundTLSOptionsContainer{
					TLS: &option.OutboundTLSOptions{
						Enabled:    true,
						Insecure:   true,
						DisableSNI: true,
					},
				},
			},
		},
		{
			Type: "direct",
			Options: &option.DirectOutboundOptions{
				DialerOptions: option.DialerOptions{
					Detour: "invalid-detour",
				},
			},
		},
		{
			Type: "shadowtls",
			Options: &option.ShadowTLSOutboundOptions{
				Version: 0,
			},
		},
		{
			Type: "juicity",
			Options: &juicity.JuicityOutboundOptions{},
		},
		{
			Type: "selector",
			Options: &option.SelectorOutboundOptions{
				Outbounds: []string{"tag1", "tag2", "tag1", "tag3", "tag2"},
				Default:   "non-existent-tag",
			},
		},
		{
			Type: "urltest",
			Options: &option.URLTestOutboundOptions{
				Outbounds: []string{"t1", "t1", "t2"},
			},
		},
		{
			Type: "loadbalance",
			Options: &loadbalance.LoadBalanceOptions{
				URLTestOutboundOptions: option.URLTestOutboundOptions{
					Outbounds: []string{"lb1", "lb2", "lb1"},
				},
			},
		},
	}

	sanitizeOutbounds(outbounds)

	// Verify AnyTLS TFO disabled
	anyOpt := outbounds[0].Options.(*option.AnyTLSOutboundOptions)
	if anyOpt.TCPFastOpen {
		t.Errorf("expected anytls TCPFastOpen to be false, got true")
	}

	// Verify Hysteria2 empty obfs removed
	hy2Opt := outbounds[1].Options.(*option.Hysteria2OutboundOptions)
	if hy2Opt.Obfs != nil {
		t.Errorf("expected hysteria2 empty password obfs to be nil, got %+v", hy2Opt.Obfs)
	}

	// Verify TUIC stream/relay conflict resolved
	tuicOpt := outbounds[2].Options.(*option.TUICOutboundOptions)
	if tuicOpt.UDPOverStream {
		t.Errorf("expected tuic UDPOverStream to be false when UDPRelayMode is set")
	}

	// Verify WireGuard detour/listen_port conflict resolved
	wgOpt := outbounds[3].Options.(*option.WireGuardEndpointOptions)
	if wgOpt.ListenPort != 0 {
		t.Errorf("expected wireguard ListenPort to be 0 when Detour is set, got %d", wgOpt.ListenPort)
	}

	// Verify Snell missing version defaulted to 4
	snellOpt := outbounds[4].Options.(*option.SnellOutboundOptions)
	if snellOpt.Version != 4 {
		t.Errorf("expected snell Version to default to 4, got %d", snellOpt.Version)
	}

	// Verify Naive TFO and insecure/disable_sni cleared
	naiveOpt := outbounds[5].Options.(*option.NaiveOutboundOptions)
	if naiveOpt.TCPFastOpen {
		t.Errorf("expected naive TCPFastOpen to be false")
	}
	if naiveOpt.TLS.Insecure || naiveOpt.TLS.DisableSNI {
		t.Errorf("expected naive TLS Insecure and DisableSNI to be false")
	}

	// Verify Direct Detour cleared
	dirOpt := outbounds[6].Options.(*option.DirectOutboundOptions)
	if dirOpt.Detour != "" {
		t.Errorf("expected direct Detour to be empty, got %s", dirOpt.Detour)
	}

	// Verify ShadowTLS TLS enabled and version defaulted
	stOpt := outbounds[7].Options.(*option.ShadowTLSOutboundOptions)
	if stOpt.TLS == nil || !stOpt.TLS.Enabled || stOpt.Version != 3 {
		t.Errorf("expected shadowtls TLS enabled and version 3, got %+v", stOpt)
	}

	// Verify Juicity TLS enabled
	jOpt := outbounds[8].Options.(*juicity.JuicityOutboundOptions)
	if jOpt.TLS == nil || !jOpt.TLS.Enabled {
		t.Errorf("expected juicity TLS enabled, got %+v", jOpt)
	}

	// Verify Selector deduplicated and non-existent default cleared
	selOpt := outbounds[9].Options.(*option.SelectorOutboundOptions)
	if len(selOpt.Outbounds) != 3 || selOpt.Default != "" {
		t.Errorf("expected selector 3 unique tags and empty default, got len=%d default=%s", len(selOpt.Outbounds), selOpt.Default)
	}

	// Verify URLTest deduplicated
	urlOpt := outbounds[10].Options.(*option.URLTestOutboundOptions)
	if len(urlOpt.Outbounds) != 2 {
		t.Errorf("expected urltest 2 unique tags, got len=%d", len(urlOpt.Outbounds))
	}

	// Verify LoadBalance deduplicated
	lbOpt := outbounds[11].Options.(*loadbalance.LoadBalanceOptions)
	if len(lbOpt.Outbounds) != 2 {
		t.Errorf("expected loadbalance 2 unique tags, got len=%d", len(lbOpt.Outbounds))
	}

	// Test sanitizeTunStack for gvisor, mixed, system
	cfg := `{"inbounds":[{"type":"tun","stack":"system"},{"type":"tun","stack":"gvisor"},{"type":"tun","stack":"mixed"}]}`
	cleanCfg := sanitizeTunStack(cfg)
	if cleanCfg != `{"inbounds":[{"type":"tun","stack":"go"},{"type":"tun","stack":"go"},{"type":"tun","stack":"go"}]}` {
		t.Errorf("unexpected sanitizeTunStack output: %s", cleanCfg)
	}
}
