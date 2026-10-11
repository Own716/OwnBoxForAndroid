# OwnBoxForAndroid 审计覆盖表 (AUDIT_COVERAGE.md)

| 模块 / 路径 | 审计范围 | 检查结果 | 已发现问题与风险 | 验证状态 |
| :--- | :--- | :--- | :--- | :--- |
| `app/build.gradle.kts` | 依赖管理与库版本 | 依赖审查完成 | 使用存在已知反序列化与 DoS 漏洞的 SnakeYAML 1.30 | 已升级为 2.2 并验证构建通过 |
| `app/src/main/.../group/RawUpdater.kt` | 订阅更新与 YAML/Clash 解析 | 代码逻辑与调用链审计完成 | `Yaml().loadAs()` 允许构造任意不受信任对象，未设深度与别名限制，输入无大小上限 | 已改造为 SafeConstructor + LoaderOptions 限制，并通过回归测试 |
| `app/src/main/.../ktx/Logs.kt` | 日志输出与敏感信息防护 | 源码门控与通道审计完成 | 未对输出日志正文过滤，低级别日志门控虽有但无法防止凭据被输出 | 已集成 LogSanitizer 敏感字段脱敏 |
| `app/src/main/.../bg/proto/ProxyInstance.kt` | 代理实例生命周期与配置日志 | 源码与调试输出审计完成 | `Logs.d(config.config)` 输出全量明文节点配置（含密码、UUID、私钥） | 已替换为 LogSanitizer.summarizeConfig 结构化摘要 |
| `app/src/main/.../bg/proto/TestInstance.kt` | 测速实例配置构建与调试日志 | 源码与调试输出审计完成 | `Logs.d(config.config)` 在 DEBUG 模式输出明文配置 | 已替换为 LogSanitizer.summarizeConfig 结构化摘要 |
| `app/src/main/.../bg/proto/SpeedTestRunner.kt` | 测速队列执行器日志 | 源码与调试输出审计完成 | `Logs.d(config.config)` 输出明文配置 | 已替换为 LogSanitizer.summarizeConfig 结构化摘要 |
| `app/src/main/res/xml/network_security_config.xml` | 网络明文配置与证书信任 | XML 配置审查完成 | 全局 `cleartextTrafficPermitted="true"` 且未明确用户证书信任锚点 | 已补充 system + user CA 信任锚点 |
| `app/src/main/res/xml/backup_rules.xml` | Android 12+ 云备份与设备迁移 | 数据提取规则审计完成 | `configuration.db` 包含 WebDAV 账号密码与密钥，被纳入了未加密云备份 | 已在 cloud-backup 中排除 configuration.db，保留 device-transfer |
| `app/src/main/res/xml/backup_descriptor.xml` | Android 11 及以下全量备份规则 | 备份描述文件审计完成 | 旧版规则允许备份 `configuration.db` | 已显式排除 configuration.db |
| `app/src/main/.../ui/BackupFragment.kt` | WebDAV 备份与 ZIP/JSON 恢复 | 还原与解压逻辑审计完成 | ZIP 解压未校验路径穿越字符，未限制解压体积，存在 Zip Slip 与炸弹风险 | 已增加路径校验与 20MB 大小限制 |
| `app/src/main/.../ui/ConfigurationFragment.kt` | 本地文件与 ZIP 导入 | 文件流与解压逻辑审计完成 | ZIP 导入未防范路径穿越与超大条目 | 已增加条目数量限制 (500) 与条目大小上限 (5MB) |
| `app/src/main/.../ui/WebDAVSettingsActivity.kt` | WebDAV 界面与配置存储 | 交互与存储审计完成 | 配置明文 http:// WebDAV 服务器时无安全警告 | 已增加针对 cleartext http 的明文传输安全警告 |
| `app/src/main/.../ui/MainActivity.kt` | 外部 Intent 与 Deep Link 处理 | Intent 与 URI 处理审计完成 | 外部导入未对 URI scheme 校验，query 参数未设边界 | 已限制 scheme 必须为 http/https，限制 encodedQuery 最大 1MB |
| `app/src/main/.../ktx/Asyncs.kt` | 协程工具函数与生命周期 | 协程调用语义审计完成 | `runBlockingOnMainDispatcher` 内部使用 `GlobalScope.launch` 无法真实阻塞 | 已修正为直接在 `Dispatchers.Main.immediate` 阻塞执行 |
| `app/src/main/.../fmt/ConfigBuilder.kt` | sing-box 配置生成与 Clash API | 配置结构与参数审计完成 | 核查 Clash API 绑定地址与密码机制 | 经审计 external_controller 严格绑定 127.0.0.1:9090，密码为 64 位安全随机字符 |
| `app/src/main/.../database/SagerDatabase.kt` | Room 数据库、版本迁移与灾备 | 数据库升级与容灾审计完成 | 核实降级与损坏恢复逻辑 | 包含 MIGRATION_9_10，损坏时创建时间戳备份，不含无条件静默删除 |
| `.github/workflows/release.yml` | 自动化发布流水线 | GitHub Actions 工作流审计完成 | workflow_dispatch 默认 tag 硬编码为 v2.3.9，缺少分支与 tag 一致性校验 | 已移除硬编码默认值并添加版本一致性前置校验 |
| `.github/workflows/preview.yml` | 预览版构建流水线 | GitHub Actions 签名审计完成 | 检查 Secrets 读取与未签名 APK 处理 | 检查确认使用 GitHub Secrets，缺少 Secrets 时构建安全退出 |
| `libcore/` (Go 核心模块) | Go 核心网络、协议与 JNI 接驳 | 交叉编译与平台逻辑审计完成 | 平台系统调用使用 Linux 套接字 API | Linux/arm64 平台交叉编译通过 (`go build ./...` exit 0) |
| `app/src/main/AndroidManifest.xml` | 组件导出与权限范围 | Manifest 清单与组件边界审计完成 | 导出组件核实 | 均包含必要权限保护或快捷方式意图，无无保护敏感导出组件 |
