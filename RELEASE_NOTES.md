# OwnBox for Android v2.5.7 预览版 (v2.5.7-preview)

## 版本更新摘要（Release Notes）

* **HY2 节点 TCP Ping 与 URL Test 连通性彻底修复**：
  - 剔除 `HysteriaFmt` 中硬编码的错误 `alpn = ["h3"]` 配置，解除与 Hysteria 2 协议标准 ALPN 冲突导致的 `EOF` 握手阻断；为 Hysteria 1/2 与 TUIC 全面引入 `serverAddress` 智能 SNI 兜底策略。
  - 废弃已失效且会产生 3 秒超时挂起的伪造 QUIC 协商包探测，对纯 UDP 协议节点（HY2、TUIC、WireGuard）直接透传由 URL Test 高效测试，彻底消除 UDP 协议探测黑洞。
* **URL Test / TCP Ping 延迟严重虚高彻底消除与算法对齐**：
  - 移除 URL Test 启动前针对测试目标域名在未代理环境下的主线程阻塞式 DNS 预解析（`InetAddress.getAllByName`），消除测速启动排队与首节点卡死问题。
  - 内核层（libcore）测速机制无缝升级为官方 `sing-box` 原生 `urltest.URLTest` 链路标准（结合 `NeedHandshakeForWrite` 与高效 HTTP HEAD 探针），仅测量真实的代理网络往返首包延迟（TTFB），彻底消除 2000ms+ 虚高读数，测速数值精准对齐主流客户端（~150-300ms）。
  - TCP Ping 计时严格隔离：将 `InetSocketAddress` 域名解析前置于计时器外，仅精确测量纯 TCP 三次握手 RTT 时间；并为测速并发协程增加超时安全保护。
* **落地 IP 详情弹窗底部按钮颜色冲突与对比度优化**：
  - 彻底重构“复制信息”、“重新测速”及“完成”底部按钮的主题色与文字对比度适配；
  - 描边线框按钮采用清晰的主题色高对比文字、图标与边框，主要操作按钮采用主题色背景搭配白色文字，全面消除浅色、纯白、深色及 AMOLED 黑色主题下的按钮隐形与不可见问题。

# OwnBox for Android v2.5.6 预览版 (v2.5.6-preview)

## 版本更新摘要（Release Notes）

* **主页底部落地 IP 卡片加高与两行排版**：底部状态栏高度参照 Throne 适度加高，右侧延迟全新重构为清晰的上下两行排版（上行严格独立展示“HTTP/HTTPS 握手延迟”，下行加粗展示具体延迟数值），彻底解决系统大字号下的文本截断与字串拼接错位。
* **路由与规则资源更新 403 频控绕过与提示**：直连 GitHub Releases 官方下载地址，彻底绕过 GitHub REST API 60次/小时的未认证 403 速率限制，并补充完善检查更新、更新成功与失败的友好 Toast 状态通知。
* **节点去重逻辑重构（保留首个/选中项）**：彻底重构右上角“删除重复的服务器”功能，保留各重复组的首个（或当前选中的）节点，仅安全剔除多余冗余节点，并平滑迁移当前选中状态与同步刷新列表。
* **URL Test 测速延迟虚高与算法对齐**：全面对齐官方 sing-box 及 Throne 测速标准，改用显式出站拨号建立隧道后单次 HTTP GET 精确测量 TTFB 延迟（~150-350ms），彻底消除包含冷启动建链的 1000~2000ms+ 虚高读数。
* **Hysteria2 与 TUIC 测速 EOF 及死锁修复**：消除人工设置的 `udpSemaphore(2)` 并发瓶颈，加入超时熔断保护，防止异常节点阻塞整队；彻底杜绝在已关闭 QUIC 流上重用连接导致的 EOF 伪失败。
* **Trojan 节点 URL Test 可用性修复**：修复构建 Trojan 出站时缺失 TLS enabled 与 server_name/SNI 导致的握手协商失败。
* **IPv6 禁用开关泄露与黑洞拦截**：解决 Android VPN 服务未捕获 IPv6 导致的物理网卡直连泄露，在 VPN 顶层完整接管 IPv6 路由与地址，并在 sing-box 内核层严格阻断并丢弃所有 IPv6 流量及 AAAA 查询。
* **极端白底与纯黑/AMOLED 主题对比度校准**：全面优化落地 IP 详情弹窗等界面在纯白和暗黑/AMOLED 模式下的背景、边框与文字对比度，文字清晰锐利易读。
