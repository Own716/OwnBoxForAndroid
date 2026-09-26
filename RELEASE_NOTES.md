# OwnBox v2.8.12-preview

* 彻底修复 IPv6 路由“优先”模式导致 IPv4 节点无法使用的严重问题
* 移除前置路由中对 Fake-IP 强制触发 `action: resolve` 的错误逻辑，保留原始域名由节点远端自适应解析与回退，绝不向纯 IPv4 节点强推无法路由的 IPv6 裸地址
* 优化远程 DNS（dns-remote）代理出站连接策略，在双栈与 IPv6 优先环境下使用安全通道，彻底消除纯 IPv4 节点下远程 DNS 超时挂死故障
* 完善节点服务器域名解析策略（domain_strategy_for_server），在“IPv6 优先”模式下优先通过 IPv6 连接双栈节点，并在 IPv6 不可用时自动在 300ms 内平滑回退 IPv4
* 直连与绕过（Direct / Bypass）出站在“IPv6 优先”模式下支持 prefer_ipv6 并自动回退，国内直连流量自适应双栈
* 完善 Fake-IP 在“仅 IPv6（ONLY）”模式下的 query_type 与地址范围过滤，严格保障 IPv6-only 语义
* 修复订阅更新批量域名解析中的地址排序逻辑，确保 ipv6First 模式正确优先选取 IPv6 地址
* 递增版本号至 2.8.12 预览版（versionCode 315），保障现有配置无缝覆盖升级
