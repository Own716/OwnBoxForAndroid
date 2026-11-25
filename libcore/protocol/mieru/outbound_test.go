package mieru

import (
	"testing"

	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/option"
)

func TestMieruRegistryAndOptions(t *testing.T) {
	registry := outbound.NewRegistry()
	RegisterOutbound(registry)

	if _, ok := registry.CreateOptions(TypeMieru); !ok {
		t.Fatalf("expected %s to be registered in outbound registry", TypeMieru)
	}
}

func TestParsePortBindings(t *testing.T) {
	cases := []struct {
		name      string
		opts      MieruOutboundOptions
		wantCount int
		wantErr   bool
	}{
		{
			name: "single_server_port",
			opts: MieruOutboundOptions{
				ServerOptions: option.ServerOptions{Server: "1.2.3.4", ServerPort: 2010},
			},
			wantCount: 1,
		},
		{
			name: "port_range",
			opts: MieruOutboundOptions{
				ServerOptions: option.ServerOptions{Server: "example.com"},
				PortRange:     "2090-2099",
			},
			wantCount: 1,
		},
		{
			name: "server_ports_colon",
			opts: MieruOutboundOptions{
				ServerOptions: option.ServerOptions{Server: "example.com"},
				ServerPorts:   []string{"2010", "3000:3010"},
			},
			wantCount: 2,
		},
		{
			name: "missing_ports",
			opts: MieruOutboundOptions{
				ServerOptions: option.ServerOptions{Server: "example.com"},
			},
			wantErr: true,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			o := &Outbound{options: tc.opts}
			if tc.opts.Username == "" {
				o.options.Username = "user"
			}
			if tc.opts.Password == "" {
				o.options.Password = "pass"
			}
			cfg, err := o.buildClientConfig()
			if tc.wantErr {
				if err == nil {
					t.Fatalf("expected error, got nil")
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if got := len(cfg.Profile.GetServers()[0].GetPortBindings()); got != tc.wantCount {
				t.Fatalf("port bindings count = %d, want %d", got, tc.wantCount)
			}
		})
	}
}
