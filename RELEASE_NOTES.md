### OwnBox v2.4.3 (Pre-release)

1. [Bug Fix] 订阅导入自动识别机场名并修改分组名称：
   - 彻底解决从剪贴板或链接导入订阅后分组名停留在默认名称（如 "My group"、"订阅" 等）的问题。
   - 强化机场名称多层级智能解析（依次根据 HTTP 响应头 profile-title / content-disposition、URL 参数、订阅二级主域名、节点公共前缀）。
   - 解析出的机场名称立即持久化保存，主界面策略分组 Tab 标题与分组列表实时更新并联动，应用重启或刷新后名称不丢失。

2. [Bug Fix] 新建与导入订阅 User-Agent 动态继承全局设置：
   - 解决在“设置”中配置全局自定义订阅 User-Agent 后，从剪贴板导入或新建订阅时仍写死旧默认 UA 的问题。
   - 默认 UA 动态绑定全局 DataStore 设置，分组未单独定制 UA 时始终实时同步全局设置变更。

3. [New Feature] 节点不可用软屏蔽 / 自动折叠（参考 Karing 机制）：
   - 支持在节点连通性测试后自动过滤/隐藏超时或连通失败的节点（保留未测试节点与可用节点）。
   - 采用纯展示层安全过滤机制，绝不修改或删除数据库中的原始节点数据。
   - 重新测速恢复可用或用户执行“清空测试结果”后，被屏蔽节点自动恢复显示。
   - 全局“设置”与节点列表右上角三点溢出菜单双向联动控制“隐藏不可用节点”开关。
   - 智能防白屏兜底保障：当分组内所有节点均不可用时，自动回退展示全部节点，防止界面空白。

4. [Stability & Performance] 全工程稳定性与列表渲染优化：
   - 完善分组数据变动总线广播机制，彻底消除多分组切换时的 Tab 标题与列表内容不同步问题。
   - 优化大规模节点列表下的状态过滤算法与 Diff 刷新性能，确保滑动顺畅无卡顿。

### OwnBox v2.4.2 (Pre-release)

1. [Bug Fix] 订阅节点完整解析与容错提升：
   - 修复包含空格和特殊字符的节点备注解析截断问题，确保节点列表无缺失完整导入。
   - 增强 V2Ray/VLESS 链接解析鲁棒性，针对 Query 中未 URL 编码的复杂 JSON 参数（如 extra={"downloadSettings":...}）进行自动转义清洗，避免节点被丢弃。
   - 完善 Clash YAML 订阅对 xhttp / splithttp 代理类型的解析支持。

2. [Bug Fix] 修复负载均衡设置崩溃：
   - 修复 BalancerSettingsActivity 在打开时因 SimpleMenuPreference.setValue() 访问未初始化的 entryValues 导致的空指针异常（NPE）崩溃。

3. [Important] 数据库平滑升级与数据安全防护：
   - 升级 Room 数据库版本至 10，提供从版本 9 到 10 的增量迁移，彻底解决从旧版本升级时由于表结构哈希变动导致本地节点与订阅被清空的问题。
   - 新增数据库失败回退安全备份机制（.bak 备份），保障用户配置资产安全。

4. [Bug Fix] 节点连通性测试遵循分组配置：
   - 单节点测速与测活现在严格优先使用所属分组或全局配置的 urlTestUrl。
   - 优化底层错误返回机制，保留主探测链接真实报错信息，避免混淆误报 Cloudflare EOF。

5. [Optimization] 流媒体解锁测试防风控与状态分类：
   - ChatGPT、Claude 等探测请求补充完整标准浏览器 Headers（Sec-Ch-Ua, Accept, Accept-Language 等），降低触发 Cloudflare WAF 的概率。
   - 对 HTTP 403 / Cloudflare 拦截做精确分类，明确显示为“受限/触发风控”，不再误报“检测超时”。

6. [New Feature] 订阅导入自动解析机场名称与策略组动态绑定：
   - 导入订阅时，依次根据 HTTP 响应头（content-disposition、profile-title、x-profile-title）、URL query 参数、订阅二级域名、节点公共前缀自动解析机场名称并命名分组。
   - 策略组 Outbound Tag 动态绑定机场名称，并自动同步重写路由规则引用，彻底消除“tag not found”异常。

7. [New Feature] 抽屉新增“连通性测试”网络工具：
   - 侧边栏“网络工具”新增连通性测试（ConnectivityTestActivity），提供三大维度探测：
     1. 大陆直连 TCP 握手（入口可达性延时）
     2. TCP RST 阻断探测（GFW 伪造重置包深度探测）
     3. 经核心出站 HTTP 延迟（代理链路全流程连通性）
