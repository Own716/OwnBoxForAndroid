module libcore

go 1.24.7

require (
	// 与 Throne 2e7182b9ea99947a409fee30f74df83752ab763c 的测速基线一致。
	github.com/Mahdi-zarei/speedtest-go v1.7.13-0.20260107171856-79c565dfd83a
	// v0.1.6签名与 v1.13.16 锁定版一致。
	github.com/exclavenetwork/sing-juicity v0.1.6
	github.com/gofrs/uuid/v5 v5.4.0
	github.com/miekg/dns v1.1.72
	github.com/oschwald/maxminddb-golang v1.13.1
	github.com/sagernet/quic-go v0.59.0-sing-box-mod.4
	github.com/sagernet/sing v0.8.12-0.20260726145744-ef2df370afca
	// 版本唯一来源是 ../nb4a.properties 的 SINGBOX_VERSION；此处仅为 Go
	// module graph 所需占位值，实际源码始终由下方 replace 指向 CI 检出的官方 tag。
	github.com/sagernet/sing-box v0.0.0
	github.com/sagernet/sing-quic v0.6.4-0.20260803041914-d83826c306d7
	github.com/sagernet/sing-tun v0.8.12-0.20260727151122-3a09076491df
	github.com/sagernet/sing-vmess v0.2.8-0.20250909125414-3aed155119a1
	github.com/ulikunitz/xz v0.5.15
	golang.org/x/crypto v0.48.0
	golang.org/x/mobile v0.0.0-20231108233038-35478a0c49da
	golang.org/x/net v0.50.0
	golang.org/x/sys v0.41.0
)

require (
	github.com/ajg/form v1.5.1 // indirect
	github.com/andybalholm/brotli v1.1.0 // indirect
	github.com/anytls/sing-anytls v0.0.11 // indirect
	github.com/caddyserver/certmagic v0.25.3-0.20260421143802-60d9d8b415d6 // indirect
	github.com/caddyserver/zerossl v0.1.5 // indirect
	github.com/cretz/bine v0.2.0 // indirect
	github.com/database64128/netx-go v0.1.1 // indirect
	github.com/database64128/tfo-go/v2 v2.3.2 // indirect
	github.com/florianl/go-nfqueue/v2 v2.0.2 // indirect
	github.com/fsnotify/fsnotify v1.9.0 // indirect
	github.com/go-chi/chi/v5 v5.2.5 // indirect
	github.com/go-chi/render v1.0.3 // indirect
	github.com/go-ole/go-ole v1.3.0 // indirect
	github.com/gobwas/httphead v0.1.0 // indirect
	github.com/gobwas/pool v0.2.1 // indirect
	github.com/godbus/dbus/v5 v5.2.2 // indirect
	github.com/google/btree v1.1.3 // indirect
	github.com/google/go-cmp v0.7.0 // indirect
	github.com/hashicorp/yamux v0.1.2 // indirect
	github.com/insomniacslk/dhcp v0.0.0-20260220084031-5adc3eb26f91 // indirect
	github.com/klauspost/compress v1.18.0 // indirect
	github.com/klauspost/cpuid/v2 v2.3.0 // indirect
	github.com/libdns/acmedns v0.5.0 // indirect
	github.com/libdns/alidns v1.0.6 // indirect
	github.com/libdns/cloudflare v0.2.2 // indirect
	github.com/libdns/libdns v1.1.1 // indirect
	github.com/logrusorgru/aurora v2.0.3+incompatible // indirect
	github.com/mdlayher/netlink v1.9.0 // indirect
	github.com/mdlayher/socket v0.5.1 // indirect
	github.com/metacubex/utls v1.8.4 // indirect
	github.com/mholt/acmez/v3 v3.1.6 // indirect
	github.com/pierrec/lz4/v4 v4.1.21 // indirect
	github.com/quic-go/qpack v0.6.0 // indirect
	github.com/sagernet/bbolt v0.0.0-20231014093535-ea5cb2fe9f0a // indirect
	github.com/sagernet/cors v1.2.1 // indirect
	github.com/sagernet/fswatch v0.1.2 // indirect
	github.com/sagernet/gvisor v0.0.0-20250811.0-sing-box-mod.1 // indirect
	github.com/sagernet/netlink v0.0.0-20240612041022-b9a21c07ac6a // indirect
	github.com/sagernet/nftables v0.3.0-mod.2 // indirect
	github.com/sagernet/sing-mux v0.3.5 // indirect
	github.com/sagernet/sing-shadowsocks v0.2.8 // indirect
	github.com/sagernet/sing-shadowsocks2 v0.2.1 // indirect
	github.com/sagernet/sing-shadowtls v0.2.1 // indirect
	github.com/sagernet/smux v1.5.50-sing-box-mod.1 // indirect
	github.com/sagernet/wireguard-go v0.0.2-beta.1.0.20260224074747-506b7631853c // indirect
	github.com/sagernet/ws v0.0.0-20231204124109-acfe8907c854 // indirect
	github.com/u-root/uio v0.0.0-20240224005618-d2acac8f3701 // indirect
	github.com/vishvananda/netns v0.0.5 // indirect
	github.com/zeebo/blake3 v0.2.4 // indirect
	go.uber.org/multierr v1.11.0 // indirect
	go.uber.org/zap v1.27.1 // indirect
	go.uber.org/zap/exp v0.3.0 // indirect
	go4.org/netipx v0.0.0-20231129151722-fdeea329fbba // indirect
	golang.org/x/exp v0.0.0-20251219203646-944ab1f22d93 // indirect
	golang.org/x/mod v0.33.0 // indirect
	golang.org/x/sync v0.19.0 // indirect
	golang.org/x/text v0.34.0 // indirect
	golang.org/x/time v0.11.0 // indirect
	golang.org/x/tools v0.42.0 // indirect
	golang.zx2c4.com/wintun v0.0.0-20230126152724-0fa3db229ce2 // indirect
	google.golang.org/genproto/googleapis/rpc v0.0.0-20251202230838-ff82c1b0f217 // indirect
	google.golang.org/grpc v1.79.1 // indirect
	google.golang.org/protobuf v1.36.11 // indirect
	lukechampine.com/blake3 v1.3.0 // indirect
)

// 官方内核：构建时由 buildScript/lib/core/get_source.sh 按 nb4a.properties 的
// SINGBOX_VERSION 克隆并校验 SagerNet/sing-box 到仓库同级目录（../../sing-box）。
replace github.com/sagernet/sing-box => ../../sing-box
