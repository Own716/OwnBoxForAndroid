# OwnBox for Android v3.2.41 预览版 (v3.2.41-preview)

* **官方最新内核基线 (sing-box v1.15.0-alpha.13)**：保持最新内核架构与原生纯 Go TUN 栈特性，全面集成以下深度网络与连接调优。
* **彻底解决 Telegram 图片首次下载长时间缓冲与转圈**：
  - **核心媒体网段穿透直达**：在路由规则中补齐 Telegram 官方核心媒体分发 CDN 网段 `91.108.20.0/22` 及 IPv6 段，彻底跳过嗅探器（Sniffer），消除非 TLS 协议产生的 100ms~300ms 首包死等；
  - **直连 DNS 极速解析优化**：将 `default_domain_resolver` 切换为系统直连物理 UDP DNS（`dns-local`），将节点域名解析耗时由 DoH 的 150~350ms 压减至 10~20ms，杜绝冷启动串行握手延迟；
  - **解除息屏亮屏断连硬杀机制**：移除息屏 > 3 分钟后亮屏强制关闭全部套接字的代码逻辑，保持 Telegram 媒体长连接在亮屏时存活温热；
  - **打通 Kotlin 与 Go 层的切网重置状态**：修复 Go 内核 `networkChangeResetConnections` 状态脱节 Bug，消除信号抖动或基站切换时内核误杀所有出站连接的问题；
  - **蜂窝网络出站 KeepAlive 注入**：配置出站 TCP KeepAlive 为 45 秒，适配国内移动蜂窝网络短 NAT 映射老化特性。
