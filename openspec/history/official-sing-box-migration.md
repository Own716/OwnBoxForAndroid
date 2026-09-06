# 官方 sing-box 大型 Breaking Change 迁移记录

## 状态

本记录由原 `ROO_KERNEL_TODO.md` 迁移而来，用于保存一次已实施的大型 Breaking Change 的背景、决策、阶段性结果和后续风险。它不是活动中的 OpenSpec change；当前有效约束以 `openspec/specs/` 为准。

## 目标与结论

项目从 starifly sing-box fork 与 libneko 旧架构迁移到 SagerNet/sing-box 官方内核。调研确认完整 husi 式 Service/Client 重写会同时波及 Go 接驳、JNI、Kotlin 服务模型和构建链，因此第一阶段采用“官方内核直换、保留现有实例模型”的策略：官方已有能力优先接入，fork 私有能力先移除、自实现或显式降级。

## 已实施

- `SINGBOX_VERSION` 成为唯一版本来源；CI 获取官方 tag，校验 commit，并将版本文件纳入 AAR 缓存键。
- 删除 libneko、starifly replace、nekoutils、旧 boxapi 和 conntrack 依赖。
- 自实现文件日志、Unix socket fd protect、URL Test、官方 v2ray stats 接入与 geo rule-set 预处理。
- 平台接口迁移到官方 `adapter.PlatformInterface`，补齐 TUN、连接属主、默认接口监视和网络接口枚举。
- 每个 box 使用独立平台 wrapper，修复并发测速覆盖 NetworkManager 的问题。
- 测试实例不注册 PlatformLogWriter，从根源避免并发共享 bbolt `cache.db` 导致的 panic。
- 默认接口监听改为首拨前同步注册；protect 服务并发处理 fd；非“VPN 未运行”故障采用 fail-fast。
- 配置对齐官方 schema：路由 sniff/resolve 动作、TUN `address`、`default_network_strategy: hybrid`、远程 DNS detour 等。
- HTTP outbound 保留 h2 ALPN 覆盖，Juicity 继续作为自定义协议接入。

## 关键事故经验

### URL 测速共享 cache.db

官方 libbox 假设单进程单实例；为每个测试实例注册 PlatformLogWriter 会隐式创建 CacheFile/ClashServer。并发测试实例共享工作目录中的 `cache.db`，可破坏 bbolt freelist，并在异步 batch goroutine 中触发不可 recover 的 panic。最终方案是测试实例不注册 PlatformLogWriter，且启动时清理历史残留缓存。

### 默认接口首拨竞态

异步注册默认接口监听会让测试 box 在缓存尚为空时拨号，报 `no available network interface`。平台接口枚举缺失也会产生相同错误。最终方案是同步注册监听、常驻预热，并完整实现官方平台接口枚举。

### protect 超时回环

吞掉 fd protect 错误会使测试流量进入 TUN，再经当前代理出站，形成“服务器自连”回环并污染测速结果。最终方案只在 VPN 明确未运行时放行，其余错误直接使拨号失败。

## 保留的已知降级

- SSR 与 Snell：官方内核不支持。
- WireGuard：官方 1.13+ 仅支持 endpoint；Android 仍需完成 outbound 到 endpoint 的配置迁移。
- Clash API/yacd 切换 selector：不触发应用原有 selector 回调；应用内切换不受影响。
- DNS hosts 规则中的历史私有字段、部分 QUIC/插件 API 兼容性仍需按实际案例验证。

## 曾规划但未整体执行的后续方向

调研曾提出按 husi 风格进一步迁移为 Service + Client Unix socket 模型、细粒度 PlatformInterface、distro/plugin 注册表、combined API、trafficcontrol、boxoption Kotlin 类生成器及 anja 构建链。这是一项新的架构级 Breaking Change；如需启动，必须另建 OpenSpec change，重新核对目标 sing-box 版本和当前代码，而不能直接照搬旧 TODO。

## 验证边界

本地不具备 Go/Android 构建环境。静态校验可在本地执行；AAR/APK 编译交由 GitHub Actions，网络切换、TUN、测速、协议与插件行为交由真机验证。
