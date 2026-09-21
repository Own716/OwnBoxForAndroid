# OwnBox v2.8.1-preview 预览版

### 内核与底层架构升级
- **升级 sing-box 内核**：全面升级至官方最新测试版 **sing-box v1.15.0-alpha.6**。
- **Sing-Tun 全新 TCP/IP 协议栈适配**：深度适配 sing-tun 自有 TCP/IP 栈，提供极佳极限吞吐、超低功耗与内存表现；Sing-Tun 模式下完全省略 stack 字段以无缝激活新栈。
- **TUN 模式向下兼容**：保留 gVisor、System、Mixed 兼容模式，并在设置界面清晰标明 `Sing-Tun (官方最新 TUN / 推荐)` 与各兼容选项。
- **全协议链兼容验证**：保持现有 VLESS (XHTTP/SplitHTTP / gRPC / WebSocket / TLS / Reality)、VMess、Trojan、Shadowsocks、Hysteria2、TUIC、Juicity、WireGuard 等代理协议完全稳定可用。
- **CI/CD 构建缓存修复**：修复 GitHub Actions 构建缓存判定逻辑，将 `nb4a.properties` 纳入构建指纹，确保内核版本升级时必自动重新拉取并编译最新官方内核。
