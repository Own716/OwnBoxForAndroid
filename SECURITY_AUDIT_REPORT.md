# OwnBoxForAndroid 安全审计报告 (SECURITY_AUDIT_REPORT.md)

## 审计概览
- **被审计仓库**: OwnBoxForAndroid (https://github.com/Own716/OwnBoxForAndroid)
- **版本基线**: v3.0.9-preview (versionCode: 359, sing-box: v1.15.0-alpha.11)
- **审计日期**: 2026-10-11
- **审计范围**: 源码架构、依赖安全、敏感凭据防护、网络传输与 TLS、数据库与备份恢复、组件安全、CI/CD 供应链安全。

---

## 漏洞与缺陷清单

### 编号：SEC-001
- **问题名称**: SnakeYAML 1.30 不安全反序列化与拒绝服务漏洞 (CVE-2022-1471 / GHSA-mjmj-j48q-9wg2 / GHSA-c4r9-r8fh-9vj2)
- **严重程度**: Critical
- **置信度**: 已确认
- **文件路径及具体行号**: `app/build.gradle.kts:86`, `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt:405-408`
- **调用链或数据流**: 远程订阅下载 / 本地 Clash YAML 导入 $\rightarrow$ `RawUpdater.parseRaw()` $\rightarrow$ `Yaml().loadAs(text, Map::class.java)`
- **受影响功能**: Clash / Meta 订阅解析与节点导入
- **触发条件**: 订阅源返回恶意构造的 YAML 自定义标签（如 `!!javax.script.ScriptEngineManager`）或超量嵌套别名（Billion Laughs）
- **实际风险**: 攻击者可通过恶意订阅链接在客户端执行任意构造器实例化或导致内存与 CPU 耗尽崩溃
- **是否可复现**: 是，构造包含自定义 Java 类型标签的 YAML 即可复现实例化行为
- **修复建议**: 升级 SnakeYAML 至安全版本 2.2，强制采用 `SafeConstructor` 并配置 `LoaderOptions`（限制代码点上限 10MB、别名上限 50、嵌套深度 50），改用 `yaml.load()` 返回安全 Map
- **是否已经修复**: 是
- **测试方式和结果**: 编写并执行单元测试 `YamlSafetyTest.kt`，验证合法配置解析正常、恶意标签与别名炸弹均被成功拦截，测试 100% 通过

---

### 编号：SEC-002
- **问题名称**: 节点密码、UUID、私钥及认证凭据全量明文写入日志缓冲区
- **严重程度**: High
- **置信度**: 已确认
- **文件路径及具体行号**: 
  - `app/src/main/java/io/nekohasekai/sagernet/bg/proto/ProxyInstance.kt:27, 41`
  - `app/src/main/java/io/nekohasekai/sagernet/bg/proto/SpeedTestRunner.kt:113`
  - `app/src/main/java/io/nekohasekai/sagernet/bg/proto/TestInstance.kt:117`
  - `app/src/main/java/io/nekohasekai/sagernet/ktx/Logs.kt:24-69`
- **调用链或数据流**: `ProxyInstance.buildConfig()` $\rightarrow$ `Logs.d(config.config)` $\rightarrow$ `Libcore.nekoLogPrintln` $\rightarrow$ 日志导出文件
- **受影响功能**: 代理核心启动、测速执行与用户日志导出
- **触发条件**: 代理节点构建或执行测速时，完整配置文本被直接送入日志器
- **实际风险**: 用户导出日志排查故障时，其所有节点的明文密码、UUID、WireGuard 私钥、Reality 私钥和认证 Token 均暴露在日志文件中，极易泄露给第三方
- **是否可复现**: 是，查看导出的日志文件即可检索到节点密码明文
- **修复建议**:
  1. 移除 `Logs.d(config.config)` 完整明文输出，改为输出安全结构化摘要 `LogSanitizer.summarizeConfig(config.config)`；
  2. 实现通用凭据脱敏工具 `LogSanitizer`，在 `Logs.kt` 全部输出通道中对 JSON 敏感键（password, uuid, private_key 等）、URL 密码、Authorization 请求头及 WireGuard 密钥执行统一正则脱敏。
- **是否已经修复**: 是
- **测试方式和结果**: 编写并执行单元测试 `LogSanitizerTest.kt`，注入 `CANARY_SECRET_FOR_TEST` 等金丝雀凭据，验证脱敏后所有敏感信息均变为 `******`，无任何泄露，测试 100% 通过

---

### 编号：SEC-003
- **问题名称**: WebDAV 凭据及本地配置数据库被纳入系统未加密云备份
- **严重程度**: High
- **置信度**: 已确认
- **文件路径及具体行号**: `app/src/main/res/xml/backup_rules.xml:8-11`, `app/src/main/res/xml/backup_descriptor.xml:6-7`
- **调用链或数据流**: Android 系统 Auto Backup $\rightarrow$ 读取 `backup_rules.xml` $\rightarrow$ 备份 `configuration.db` 至云端
- **受影响功能**: Android 系统自动云备份
- **触发条件**: 用户开启系统 Google 账号云端自动备份
- **实际风险**: `configuration.db` 中存储有用户的 WebDAV 账号密码、Clash API 密钥等敏感信息，未加密备份至云端存在凭据被截获或滥用风险
- **是否可复现**: 是，查看 `backup_rules.xml` 的 `<cloud-backup>` 标签可确认包含 `configuration.db`
- **修复建议**: 在 `<cloud-backup>` 中排除 `configuration.db`，保留 `<device-transfer>` 中的迁移包含项，确保换机迁移正常但云端不存储敏感凭据
- **是否已经修复**: 是
- **测试方式和结果**: 核验 `backup_rules.xml` 与 `backup_descriptor.xml` 声明规则，排除 `configuration.db`，保留设备对设备迁移通道

---

### 编号：SEC-004
- **问题名称**: WebDAV 恢复与本地 ZIP 导入缺乏路径校验与解压大小上限（Zip Slip 与解压炸弹风险）
- **严重程度**: Medium
- **置信度**: 已确认
- **文件路径及具体行号**: `app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt:442-450`, `app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt:1003-1011`
- **调用链或数据流**: 用户从 WebDAV 恢复备份或导入本地 ZIP $\rightarrow$ `ZipInputStream` 直接读取条目
- **受影响功能**: 备份恢复与批量配置导入
- **触发条件**: 导入包含 `../` 恶意条目名或包含超大解压体积的压缩包
- **实际风险**: 路径穿越潜在风险与内存溢出崩溃（OOM）
- **是否可复现**: 是，构造包含多级目录穿越名称与超长内容的 ZIP 即可触发
- **修复建议**: 严格校验 ZIP 条目名称不得包含 `..`、以 `/` 或 `\` 开头；设置最大解压字节上限（Backup 限制 20MB，普通条目限制 5MB，单包条目上限 500）
- **是否已经修复**: 是
- **测试方式和结果**: 经代码静态复核与单元测试回归验证

---

### 编号：SEC-005
- **问题名称**: 外部订阅 Intent 导入缺乏 URL Scheme 校验与数据大小边界
- **严重程度**: Medium
- **置信度**: 已确认
- **文件路径及具体行号**: `app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt:341-352`
- **调用链或数据流**: 外部应用发送 `sn://subscription?url=...` 或 `clash://...` Intent $\rightarrow$ `MainActivity.onNewIntent()` $\rightarrow$ `importSubscription()`
- **受影响功能**: 外部订阅与节点导入 Deep Link
- **触发条件**: 恶意第三方应用发送携带非法 scheme 或超长 base64 参数的 Intent
- **实际风险**: 传递非法协议（如 file://、javascript:）可能导致意外解析异常，超长数据导致内存过载
- **是否可复现**: 是，构造恶意 Intent 即可验证
- **修复建议**: 校验订阅 URL 必须为 `http://` 或 `https://`，限制 encodedQuery 长度不超过 1MB
- **是否已经修复**: 是
- **测试方式和结果**: 静态分析确认过滤与边界防护已生效，并保留了原有用户弹窗确认机制

---

### 编号：SEC-006
- **问题名称**: Release 自动化发布工作流默认 tag 硬编码与版本校验缺失
- **严重程度**: Medium
- **置信度**: 已确认
- **文件路径及具体行号**: `.github/workflows/release.yml:10-11, 175-188`
- **调用链或数据流**: GitHub Actions `workflow_dispatch` 手动触发 $\rightarrow$ 默认使用 `v2.3.9` $\rightarrow$ 构建 `main` 分支代码并发布错误 tag
- **受影响功能**: CI/CD 正式版本自动化发布
- **触发条件**: 维护人员手动触发工作流时未手动覆盖默认输入框
- **实际风险**: 将当前主分支（v3.0.9）打包的 APK 意外发布到过期的 `v2.3.9` 标签下，造成版本覆盖与用户升级混乱
- **是否可复现**: 是，查看 workflow_dispatch 参数配置即可确认
- **修复建议**: 移除硬编码旧默认值，在 `publish` 阶段增加版本一致性断言脚本，验证目标 tag 必须与 `nb4a.properties` 中声明的 `VERSION_NAME` 一致
- **是否已经修复**: 是
- **测试方式和结果**: workflow 脚本审查通过，逻辑断言覆盖完全

---

### 编号：SEC-007
- **问题名称**: 协程工具类 `runBlockingOnMainDispatcher` 伪阻塞逻辑缺陷
- **严重程度**: Low
- **置信度**: 已确认
- **文件路径及具体行号**: `app/src/main/java/io/nekohasekai/sagernet/ktx/Asyncs.kt:34-38`
- **调用链或数据流**: `runBlockingOnMainDispatcher { ... }` 内部调用 `runBlocking { GlobalScope.launch(...) }`
- **受影响功能**: 协程同步辅助工具
- **触发条件**: 调用该函数期望在主线程同步等待执行完成
- **实际风险**: 内部使用 `GlobalScope.launch` 为异步并发，外层 `runBlocking` 在启动后立即返回，造成竞态与状态未完成假象
- **是否可复现**: 是，通过协程完成顺序断言可证明执行未被真正阻塞
- **修复建议**: 修改为 `runBlocking(Dispatchers.Main.immediate, block = block)`
- **是否已经修复**: 是
- **测试方式和结果**: 修复后通过全量单元测试
