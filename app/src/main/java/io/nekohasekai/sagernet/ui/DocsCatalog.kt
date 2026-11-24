package io.nekohasekai.sagernet.ui

data class DocCategory(
    val id: String,
    val chipLabel: String,
    val headerTitle: String,
    val headerDesc: String,
)

data class DocEntry(
    val id: String,
    val categoryId: String,
    val title: String,
    val badge: String,
    val summary: String,
    val desc: String,
    val notes: String,
    val recommendation: String,
    val keywords: String,
)

sealed class DocListItem {
    data class Header(val category: DocCategory) : DocListItem()
    data class Entry(val entry: DocEntry) : DocListItem()
}

object DocsCatalog {

    const val CATEGORY_ALL = "all"

    val categories: List<DocCategory> = listOf(
        DocCategory(
            id = "quick_start",
            chipLabel = "快速上手",
            headerTitle = "1. 快速上手",
            headerDesc = "从导入订阅、测速到开启代理与验证出口，30 秒完成首次配置",
        ),
        DocCategory(
            id = "home_subscription",
            chipLabel = "主页与订阅",
            headerTitle = "2. 主页与订阅",
            headerDesc = "主页卡片视图、批量多选操作、测速规则与机场订阅分组管理",
        ),
        DocCategory(
            id = "strategy_group",
            chipLabel = "策略组",
            headerTitle = "3. 自动选择策略组",
            headerDesc = "统一自动选择引擎、多层级优先级、冷启动秒连与真实连接自愈",
        ),
        DocCategory(
            id = "ui_notification",
            chipLabel = "界面与通知",
            headerTitle = "4. 界面与通知",
            headerDesc = "实况通知胶囊、通知栏网速排版、外观缩放、图标定制与桌面小组件",
        ),
        DocCategory(
            id = "vpn_perapp",
            chipLabel = "VPN 与分应用",
            headerTitle = "5. VPN 与分应用代理",
            headerDesc = "虚拟网卡路由、Sing-Tun 协议栈、局域网共享与 Google Play 下载联动",
        ),
        DocCategory(
            id = "core_routing",
            chipLabel = "核心与路由",
            headerTitle = "6. 核心与路由分流",
            headerDesc = "智能分流区域、阻断 QUIC、IPv6 策略、规则匹配原则与 RE2 正则校验",
        ),
        DocCategory(
            id = "dns_speedtest",
            chipLabel = "DNS 与测速",
            headerTitle = "7. DNS 与节点测速",
            headerDesc = "国内外防污染解析、FakeDNS 原理、TLS 分片抗阻断与测速参数",
        ),
        DocCategory(
            id = "advanced_tools",
            chipLabel = "进阶与工具",
            headerTitle = "8. 进阶与实用工具",
            headerDesc = "智能启停、切网策略、安全调试、网络诊断、Sing-box 仪表盘与云备份",
        ),
        DocCategory(
            id = "faq",
            chipLabel = "疑难 FAQ",
            headerTitle = "9. 常见疑难排查",
            headerDesc = "聚焦 Wi-Fi 异常、连上打不开网页、后台延迟、容差配置与端口同步故障",
        ),
    )

    val entries: List<DocEntry> = listOf(
        // 1. 快速上手
        DocEntry(
            id = "quick_start_workflow",
            categoryId = "quick_start",
            title = "30 秒首次上手配置流程",
            badge = "新手必读",
            summary = "导入订阅或节点后先批量测速，再选定单节点或策略组连接，最后看底部落地 IP 验证出境成功。",
            desc = "首次安装 OwnBox 后，按以下 4 步即可完成稳定配置：\n" +
                "1. 导入节点或订阅：点击主页右上角菜单新建分组填入机场订阅链接，或直接从剪贴板 / 二维码导入节点。\n" +
                "2. 批量测速筛选：在分组页面点击测速执行 URL 测试，观察哪些节点延迟低且稳定可用。\n" +
                "3. 选择出站目标：可直接点选单个低延迟节点；若节点较多，建议新建一个「自动选择策略组」将可用节点纳入管理。\n" +
                "4. 开启连接并核验：点击底部连接按钮；连接成功后观察底部状态栏的落地 IP 与国家地区信息，确认显示境外出口即可正常使用。",
            notes = "若首次连接时系统弹出 VPN 授权请求，请点击允许；建议同步将 OwnBox 加入手机系统电池优化白名单，防止息屏被后台清理。",
            recommendation = "按顺序完成“导入 -> 测速 -> 选节点或策略组 -> 核验落地 IP”即可安心使用，其余高级设置保持默认黄金参数。",
            keywords = "快速上手 新手 入门 导入 订阅 测速 连接 落地 IP quick start",
        ),
        DocEntry(
            id = "quick_start_proxy_mode",
            categoryId = "quick_start",
            title = "代理模式选择：智能分流、全局模式与分应用代理",
            badge = "推荐: 智能分流",
            summary = "侧边栏提供智能分流与全局模式快速切换，日常配合智能分流可兼顾国内秒开与海外加速。",
            desc = "在左侧边栏中可随时切换核心分流模式：\n" +
                "1. 智能分流：自动识别国内域名、国内 IP 与局域网流量并直连放行，仅将海外域名与被规则命中的流量送往代理出站。\n" +
                "2. 全局模式：除局域网与代理节点自身回环地址外，其余全部流量统一经代理节点转发，适合排查分流漏网站点或临时全域代理。\n" +
                "3. 分应用代理：可独立指定只代理部分海外应用（白名单），或仅让网银、国内视频等应用绕过 VPN（黑名单），与智能分流互相叠加生效。",
            notes = "全局模式下访问国内网站和视频会绕道境外服务器，导致速度变慢且多耗机场流量，日常不建议长期停留全局模式。",
            recommendation = "日常推荐保持「智能分流」；如需微信、网银等纯物理直连，可叠加开启分应用代理绕过名单。",
            keywords = "智能分流 全局模式 分应用代理 侧边栏 代理模式 route global",
        ),

        // 2. 主页与订阅
        DocEntry(
            id = "home_card_view_and_speed",
            categoryId = "home_subscription",
            title = "卡片视图与测速结果显示规则",
            badge = "视图自适应",
            summary = "紧凑/简洁卡片下延迟与下载速度最新互斥替换；详细视图双行并存显示延迟与速率。",
            desc = "在主页「配置」页右上角切换卡片视图风格时，节点卡片上的测速结果显示规则如下：\n" +
                "1. 紧凑卡片与单行简洁行：为保证列表一屏容纳更多节点且排版整洁，卡片右侧采用“单一展示位·最新互斥替换”机制。刚测完延迟时显示 RTT 延迟；接着测下载速度时替换为实时下载速率；再次测延迟时又切换回延迟结果。\n" +
                "2. 详细卡片（普通视图）：卡片拥有充裕的底部信息行，采用双行并存展示——第一行显示 URL/TCP 延迟结果，第二行同步保留最新一次速度测试的下载/上传速率，两者互不覆盖。",
            notes = "若想同时对比一批节点的延迟与真实下载带宽，建议临时切换到详细视图；日常列表选点推荐紧凑模式更省空间。",
            recommendation = "日常选节点使用紧凑卡片或简洁风格；批量对比多节点延迟与速率时可切换至普通详细视图。",
            keywords = "卡片视图 紧凑 简洁 普通视图 测速显示 延迟 速率 互斥替换 profileCardStyle profileViewMode compactProfileRows",
        ),
        DocEntry(
            id = "home_batch_multiselect",
            categoryId = "home_subscription",
            title = "主页批量多选：全选、反选、多选测速与删除",
            badge = "高效管理",
            summary = "右上角菜单开启「多选」，支持反选节点、只测选中节点的延迟或下载速度，以及安全批量删除。",
            desc = "主页「配置」右上角菜单新增「多选」模式，适合快速清理失效节点或局部复测部分地区节点：\n" +
                "1. 多选操作栏：进入后顶部切换为批量操作栏，依次提供「反选」「URL 延迟测试」「速度测试本组」「批量删除」以及全选/取消全选。\n" +
                "2. 反选功能：已选中的取消、未选中的一键选中，配合“先点几个好节点再反选”，可快速批量删除其余不需要的节点。\n" +
                "3. 针对性测速：仅对勾选中的节点执行 URL 测试或速度测试本组，避免全组几十上百个节点全部重测耗时耗流量。\n" +
                "4. 安全保护：批量删除前触发二次确认弹窗，并自动对正在运行连接的节点做安全保护，避免误删导致当前断网。",
            notes = "未选中任何节点时点击测速或删除会提示先勾选节点；删除操作不可撤销，请留意确认框中的数量提示。",
            recommendation = "节点较多时善用「多选 + 反选」过滤特定区域，比逐条左滑或点菜单删快得多。",
            keywords = "多选 批量 反选 全选 批量删除 多选测速 速度测试本组 url测试 action_multi_select",
        ),
        DocEntry(
            id = "home_single_speed_test",
            categoryId = "home_subscription",
            title = "单节点延迟测速、速度测试与自定义下载源",
            badge = "按需带宽测速",
            summary = "支持对单个节点单独测延迟或测下载速度，并可在测速设置中自定义下载测速文件 URL。",
            desc = "除全组和批量测速外，点击单个节点卡片右上角菜单可随时执行单项体检：\n" +
                "1. URL 测试 / TCP Ping：仅向当前节点发起一次轻量延迟探测，几百毫秒内确认节点是否存活，消耗流量几乎为零。\n" +
                "2. 速度测试：建立真实加密代理通道并下载测速文件，测出单节点的真实下行吞吐带宽。\n" +
                "3. 自定义下载测速源：可在「设置 - 连接观测与负载均衡 - 简单下载测速 URL」中替换为更贴近自己目标地区的测试文件地址，解决默认源受限于地区速度不准的问题。",
            notes = "下载测速会真实消耗机场套餐流量（单节点每次数兆至数十兆不等），有限流量套餐用户请避免高频对大量节点连测下载速度。",
            recommendation = "日常排查节点连通性优先用单节点 URL 测试；仅在挑主力看片节点时按需对少量低延迟节点做速度测试。",
            keywords = "单节点测速 速度测试 下载测速 自定义下载源 simpleDownloadURL speed test",
        ),
        DocEntry(
            id = "home_subscription_card_and_update",
            categoryId = "home_subscription",
            title = "机场资产卡片与订阅更新弹窗策略",
            badge = "推荐: 开启卡片",
            summary = "置顶卡片展示已用流量与到期时间并支持直接点击更新；手动更新弹出详细变更，后台自动更新静默无打扰。",
            desc = "订阅卡片与更新反馈机制围绕“主动操作清晰可见、后台任务零打扰”设计：\n" +
                "1. 机场资产卡片：开启「显示订阅信息卡片」后，对应分组顶部会置顶展示已用流量、剩余额度、总量及到期时间，卡片内也提供一键刷新订阅按钮。\n" +
                "2. 手动更新必弹窗：当用户在主页卡片或「分组」页手动点击「更新当前订阅」或「全部更新」时，无论节点是否有增删变动，均会弹出详情弹窗展示更新结果；点击全部更新时会按订阅依次弹出各自详情。\n" +
                "3. 后台更新全静默：当用户开启了定时自动更新或打开应用触发静默检查时，后台更新全程不会弹出任何弹窗打扰当前使用。",
            notes = "机场卡片依赖订阅服务器响应头中的 Subscription-Userinfo 字段；若某机场未返回该字段，卡片将不显示流量与到期数据。",
            recommendation = "推荐开启置顶机场资产卡片；日常开启后台静默自动更新，需核对节点增删变动时随时手动点击更新看详情。",
            keywords = "机场卡片 资产 流量 剩余 到期 更新订阅 全部更新 静默 详情弹窗 show_subscription_info_card",
        ),
        DocEntry(
            id = "group_subscription_advanced",
            categoryId = "home_subscription",
            title = "订阅分组精细设置：保留有效节点、定时更新与设备 HWID",
            badge = "推荐: 保留有效节点",
            summary = "支持保留历史节点、去重过滤、常用预设自动更新间隔，以及可选发送设备与 HWID 信息适配面板限制。",
            desc = "进入「分组」点击编辑某个订阅分组，可精细控制节点同步策略：\n" +
                "1. 保留有效节点：开启后每次拉取新订阅，若旧节点不在新列表但仍需要防误删保留，或用于对比有效可用节点时提供保护，配合更新报告一目了然。\n" +
                "2. 节点去重与名称过滤：支持自动剔除重复配置，以及通过正则表达式配置「保留匹配（白名单）」或「排除匹配（黑名单）」，例如过滤掉“剩余流量/到期提醒”等提示性假节点。\n" +
                "3. 自动更新间隔与时机：提供 15 分钟至 24 小时快捷预设及自由单位弹窗（最小 15 分钟），支持设置「仅已连接时更新」（避免直连订阅域名被阻断）以及自定义并锁定 User-Agent。\n" +
                "4. 发送设备与 HWID 信息：可在「设置 - 进阶设置」按需开启「订阅更新发送设备与 HWID 信息」（默认关闭）。开启后更新或导入订阅将附带设备标识与机型请求头，兼容 Remnawave、Marzban、3X-UI 等限制设备绑定数量的面板；若服务端返回设备超限或缺少标识会给出明确中文引导。\n" +
                "5. 分组链式与强制解析：支持为整组统一指定前置代理或落地代理，并可按需开启强制提前解析服务器域名。",
            notes = "若某订阅链接直连经常拉取失败，可开启「仅当连接时更新」并在 OwnBox 连接状态下刷新；开启 HWID 时设备标识仅在本地生成，不会在跨设备恢复备份时互相覆盖冲突。",
            recommendation = "建议在订阅分组中配置名称黑名单过滤“官网/过期/倍率说明”等非可用行；普通机场保持 HWID 开关关闭，仅使用设备限制面板的订阅按需开启。",
            keywords = "分组 设置 保留有效节点 去重 正则过滤 白名单 黑名单 User-Agent 仅连接时更新 自动更新 预设 强制解析 HWID 设备绑定 Remnawave Marzban 3X-UI",
        ),

        // 3. 策略组
        DocEntry(
            id = "strategy_selection_modes",
            categoryId = "strategy_group",
            title = "自动选择三种模式：最快最稳、按优先级顺序与故障转移",
            badge = "日常首选: 最快最稳",
            summary = "提供「最快最稳」「按优先级顺序」与「故障转移 (Fallback)」三种选路方式，覆盖测速优选、层级主备与顺位容灾。",
            desc = "OwnBox 策略组采用统一的「自动选择」引擎，支持三种选路方式：\n" +
                "1. 最快最稳：根据组内各节点最新的实测延迟与健康可用状态，始终自动将流量切向延迟最低且健康的可用节点，仅在超过切换容差时平顺切换。\n" +
                "2. 按优先级顺序：支持点开「添加层级」按订阅分组或节点名称正则划分层级（未匹配节点自动归入保底层级），也可点击「节点优先级」进入独立二级页面拖动排序或按当前延迟重排。\n" +
                "   运行机制：优先使用最高优先级层级内的最快健康节点；若该层全数不可用，立即自动故障转移到下一层级；高顺位节点恢复可用且测速通过后立即回切。\n" +
                "3. 故障转移 (Fallback)：严格按指定顺位优先使用主节点，仅当主节点连续探测失败达到设定的「连续失败容错次数」时，才按顺序切换到下一个健康备用节点；可开关控制主节点恢复后是否自动切回。",
            notes = "「按优先级顺序」适合“自建主力层 + 机场备胎层”的多层级混合调度；「故障转移 (Fallback)」适合明确按 1 号、2 号、3 号顺序稳态托底的主备场景。",
            recommendation = "纯机场多节点追求省心与低延迟优先选「最快最稳」；需要分层级自动择优选择「按优先级顺序」；需严格顺位主备兜底选「故障转移」。",
            keywords = "策略组 自动选择 最快最稳 按优先级顺序 故障转移 Fallback 自动切回 容错次数 层级 节点优先级 排序 balancerSelectionMode",
        ),
        DocEntry(
            id = "strategy_member_source_filter",
            categoryId = "strategy_group",
            title = "策略组节点来源混搭、过滤与链式中转",
            badge = "灵活组合",
            summary = "支持同时选取整个订阅分组与额外散装节点并自动去重，支持国家代码、名称正则排除及前置/落地代理。",
            desc = "编辑策略组时可灵活拼装节点池与链路拓扑：\n" +
                "1. 分组 + 散装节点混搭：可同时绑定整个服务器分组，并叠加挑选其他分组里的散装单节点，系统自动去重合并。\n" +
                "2. 精准筛选与排除：展开高级配置中的选点过滤条件，可通过名称包含/排除正则以及国家代码筛选目标节点（编辑页预览摘要实时显示实际匹配数量）。\n" +
                "3. 链式代理与隔离：支持在策略组高级设置内直接绑定前置代理或落地代理，各策略组出站独立隔离；策略组本身也可作为跳点被加入「链式代理」中使用。",
            notes = "匹配数量会直接在策略组编辑页预览区实时显示；如果写错名称正则表达式，界面会即时提示语法错误以免影响保存。",
            recommendation = "若只想把香港和日本低倍率节点放入一个策略组，绑定对应订阅分组后再配合包含正则（如 香港|HK|日本|JP）最直观高效。",
            keywords = "策略组 来源 分组 散装节点 混搭 过滤 正则 国家 前置代理 落地代理 链式代理",
        ),
        DocEntry(
            id = "strategy_cold_start_and_probe",
            categoryId = "strategy_group",
            title = "冷启动秒连预热、流式就绪与严格间隔调度",
            badge = "核心极速机制",
            summary = "启动第 0 毫秒预热历史延迟秒选好节点，测速好节点率先流式就绪不被死节点拖满 5 秒，严格遵守检查间隔。",
            desc = "OwnBox 对策略组底层建连与探测时序进行了全链路优化：\n" +
                "1. 冷启动种子预热：连接启动时直接将数据库中各节点最近的可用延迟注入内核，第 0 毫秒即直接锁定最快健康节点，不再盲连排在最前面的超时节点。\n" +
                "2. 并发测速流式就绪：发起并发探测时，一旦最快健康节点率先返回（如 150ms）即刻触发激活上岗，绝不等待池子里其他死节点耗尽 5 秒超时。\n" +
                "3. 1 次失败即切换与 2 秒快速故障转移：本轮测速单次失败立刻判为不可用并切往备用节点；实际业务拨号时若当前主选在 2 秒内未建立连接，立即跳过尝试下一健康候选。\n" +
                "4. 严格执行检查间隔：每轮全部测完后严格等待用户设定的检查间隔（默认 300 秒 / 5 分钟）再进行下一次巡检，期间不偷测；仅在系统真实切网（如 Wi-Fi 与 5G 互切）后触发 1 次重测。",
            notes = "冷启动在极短时间窗内先由低延迟好节点承接首包，同轮后续如有明显更快的节点返回且差值超过切换容差，会在同轮不到 1 秒内平顺纠偏切换，无需等待下个 300 秒周期。",
            recommendation = "默认 300 秒检查间隔与并发探测已针对冷启动和省电做好平衡，日常无需频繁缩短至十几秒以免增加无谓测试流量。",
            keywords = "冷启动 秒连 预热 流式 测速 300秒 检查间隔 故障转移 切网 重测 1次失败",
        ),
        DocEntry(
            id = "strategy_tolerance_and_interrupt",
            categoryId = "strategy_group",
            title = "切换容差与切换时断开连接控制",
            badge = "默认: 容差 30 ms",
            summary = "按用户设置的毫秒容差决定是否切换，支持精确 0 ms；切换断连开关统一控制普通、故障与回切行为。",
            desc = "策略组「高级」->「健康检查与切换」提供精细调控选项：\n" +
                "1. 切换容差（默认 30 ms）：用于「最快最稳」及「按优先级顺序」同层级内择优——仅当新候选节点的实测延迟比当前正在使用的节点低且差值严格大于容差值时才执行切换（例如当前 120 ms、容差 30 ms，新节点需低于 90 ms 才换）。设成 0 ms 则严格毫秒必争，只要更低就切换。「故障转移」模式下按顺位主备生效，不适用容差。\n" +
                "2. 切换时断开连接（默认关闭）：控制发生切换时（含普通择优、故障切换、优先级回切）是否立即强行中断旧节点上的既有 TCP/UDP 长连接。\n" +
                "   - 关闭时（推荐）：已有会话（如正在下载的文件、直播、长连接聊天）继续平稳走完不断流，新发起的连接立即全部切换至新节点。\n" +
                "   - 开启时：一旦切节点立刻斩断全部老连接强制全部流量秒切新节点，适合必须第一时间踢掉卡死旧长连接的场景。",
            notes = "容差过小且公网抖动大时可能频繁切点；若「切换时断开连接」处于开启状态，频繁切点会导致观看视频或游戏重新握手断线。",
            recommendation = "日常推荐保持「切换容差 30 ms」与「关闭切换断连」；如果对切换新节点实时性要求极高，可开启切换断连或把容差调为 10–20 ms。",
            keywords = "容差 tolerance 30ms 50ms 0ms 切换时断开连接 断流 平滑 策略组 balancerTolerance",
        ),
        DocEntry(
            id = "strategy_traffic_balance",
            categoryId = "strategy_group",
            title = "在最佳配置之间分配流量（轮换与按连接分散）",
            badge = "默认: 关闭",
            summary = "支持将流量分散到多个延迟接近容差范围内的健康节点，可选按时间轮换或按连接分散。",
            desc = "开启策略组编辑页中的「在最佳配置之间分配流量」后，系统会筛选出当前全部健康、且延迟与最优节点差距在容差范围内的节点组成“可用共享池”，并提供两种分发方式：\n" +
                "1. 按时间轮换（默认）：每经过设定的轮换间隔秒数，自动切换到共享池中的下一个健康节点承接后续新连接。\n" +
                "2. 按连接分散：每收到一条新的 TCP/UDP 连接请求就按顺序轮流分配给池中的不同节点，适合多线程并发下载或采集场景。",
            notes = "「按连接分散」模式下同一个网站的连续不同请求可能落到不同出口 IP 上，若同时访问对会话和 IP 一致性敏感的银行、支付或 AI 平台，可能触发频繁验证或登录风控。",
            recommendation = "普通网页浏览与登录境外账号场景保持关闭（单主力优选模式）；仅大流量多线程下载等场景按需启用。",
            keywords = "分配流量 负载均衡 按时间轮换 按连接分散 per_connection timed_rotation balancerBalanceEnabled",
        ),
        DocEntry(
            id = "strategy_learn_from_connections",
            categoryId = "strategy_group",
            title = "从真实连接中学习：站点记忆与单站避让",
            badge = "推荐: 开启",
            summary = "开启后记住每个网站最近成功可用的节点、单站连败自动避让约 10 分钟并在连续连接失败时自动切备用。",
            desc = "开启策略组「高级」中的「从真实连接中学习」后（未开启负载均衡分配流量时完全发挥站点记忆效果），内核会在真实业务流量转发中自动记录连接质量：\n" +
                "1. 网站选择记忆：按主域名记住该网站最近一次连通顺畅的可用节点，只要该节点仍健康可用，再次访问同一网站时优先保持同一出口（稳住账号登录态），无需切来切去。\n" +
                "2. 单站故障避让：如果某节点在访问某个特定网站时屡次出现连接失败或无响应关闭，只会在约 10 分钟内对该特定网站避让改走其他节点（不影响该节点服务其他正常网站，也不误伤全部用户规则）。\n" +
                "3. 连续失败主动切换：若当前使用节点在真实业务里连续出现连接失败，触发快速切换备用。\n" +
                "4. 关闭即彻底停用：如果不需要任何连接记忆，直接关闭开关即可完全停用。",
            notes = "本功能只负责网站匹配容错与黑洞感知，不会篡改用户设置的检查间隔与切换容差，也不会自行对节点做数分钟全局封锁。",
            recommendation = "推荐开启。既能在节点偶发对个别站点被墙或被限流时自动切其他节点绕行，又能保持访问常用站点的出口连续性。",
            keywords = "从真实连接中学习 站点记忆 单站避让 被动学习 连续失败 清空记忆 clear_learned learn_from_connections",
        ),
        DocEntry(
            id = "strategy_monitor_console",
            categoryId = "strategy_group",
            title = "主页策略横幅与策略监控控制台",
            badge = "实时透明掌控",
            summary = "连上策略组时主页显示当前使用节点与延迟，点开监控页可看倒计时、一键检查及严格手动锁定节点。",
            desc = "开启任一策略组作为主连接后，无需启用外部 Clash API 即可直接使用内置策略监控控制台：\n" +
                "1. 主页策略状态横幅：在分组 Tab 栏下方实时显示当前策略模式、正在承载流量的节点名称以及最新延迟（若处于手动锁定且锁定节点不可用，横幅将高亮红字提醒）。\n" +
                "2. 监控控制台详情：点击横幅进入控制台，顶部展示下次检查精确倒计时、可用节点数、已记住站点数与上次切换原因；列表内将置顶/使用中排在上方、备用就绪居中、不可用节点归类沉底便于查看，并转译出清晰的中文错误原因与失败次数比例。\n" +
                "3. 手动操作：点击任意节点即可执行「严格手动锁定」（锁死在该节点直到用户点击「恢复自动」）；右上角支持「立即全部检查」以及「清空网站记忆」。",
            notes = "监控页中的沉底排序仅为前端界面视觉分类展示，便于快速浏览健康与故障节点，不代表任何底层必须按视觉顺位连接的限制。",
            recommendation = "当某网站疑似抽风时，点开主页横幅看看是不是当前主力节点波动，可一键点击「立即全部检查」刷新全部成员状态。",
            keywords = "策略监控 横幅 控制台 严格锁定 恢复自动 立即全部检查 倒计时 中文错误 沉底 备用就绪",
        ),

        // 4. 界面与通知
        DocEntry(
            id = "ui_live_update_island",
            categoryId = "ui_notification",
            title = "实况通知与灵动胶囊自定义模板",
            badge = "Android 15+ 推荐",
            summary = "在状态栏以灵动胶囊或流体云样式展示连接状态与速率，支持多套预设与自定义变量占位符。",
            desc = "在「设置 - 界面设置 - 通知栏」中开启「实况通知」后，状态栏与锁屏通知升级为现代化动态胶囊与富媒体卡片：\n" +
                "1. 收起时显示灵动胶囊：在支持 Android 实况胶囊、ColorOS 流体云或 HyperOS 焦点通知的系统上，于状态栏紧凑呈现实况文字；关闭则仅保留展开卡片样式。\n" +
                "2. 五套模板预设：内置「策略组模式」「节点优先模式」「极简 IP 模式」「全能监控模式」以及「完全自定义」。\n" +
                "3. 自定义变量格式：可分别定制收起胶囊文字、展开主标题、展开内容与次要摘要，并随时点击「效果预览与变量说明」即时查看渲染效果。支持 {profile}、{group}、{ip}、{country}、{down}、{up}、{traffic_total}、{duration}、{latency}、{direct_down}、{direct_up} 等全部变量。",
            notes = "部分非支持原生实况胶囊或流体云的旧版本手机系统会自动降级为标准展开常驻通知卡片展示，不影响底层代理使用。",
            recommendation = "追求状态栏一眼查看当前节点或出口国家的，推荐开启「实况通知」并直接选用「策略组模式」或「极简 IP 模式」预设。",
            keywords = "实况通知 灵动岛 灵动胶囊 流体云 焦点通知 预设 模板 占位符 liveUpdateEnabled liveUpdatePreset",
        ),
        DocEntry(
            id = "ui_notification_speed_and_group",
            categoryId = "ui_notification",
            title = "通知栏网速、直连速度、流量统计与显示分组",
            badge = "推荐: 开启统计与直连",
            summary = "折叠单行展示代理速率、展开通知独立成行展示直连速率；支持自定义刷新间隔、分组显示与历史流量统计。",
            desc = "通知栏数据呈现包含四项基础配置：\n" +
                "1. 显示直连的速度：采用折叠首屏优先与分行独立排布设计——折叠态仅紧凑单行展示代理出站网速；下拉展开通知后，直连网速独立成行展示（直连: ↑.. ↓..），轻松察觉国内应用后台走流。\n" +
                "2. 速率更新间隔（默认 1000ms）：控制主页底部状态栏与通知栏速度数字的刷新频率（如 500ms / 1000ms / 2000ms）。\n" +
                "3. 节点流量统计（默认开启）：累计保存每个节点历史上产生的上传/下载字节总量，便于和机场账单对账。\n" +
                "4. 通知栏显示分组：控制是否在通知标题展示所属订阅分组名或策略组名，在策略组下可清楚看到“当前策略组 + 实际落地节点”。",
            notes = "过短的刷新间隔（如 200ms）会增加低端机型主线程每秒重绘次数，日常没有必要设置得比 1000ms 更频繁。",
            recommendation = "推荐开启「显示直连的速度」「节点流量统计」和「通知栏显示分组」，速率更新间隔保持默认的 1000ms。",
            keywords = "直连速度 速率更新间隔 流量统计 显示分组 通知栏 showDirectSpeed speedInterval profileTrafficStatistics showGroupInNotification",
        ),
        DocEntry(
            id = "ui_notification_actions_and_lockscreen",
            categoryId = "ui_notification",
            title = "通知快捷操作按钮与系统/锁屏通知权限",
            badge = "按需自定义",
            summary = "可自由挑选最多 3 个通知栏快捷操作按钮，并一键直达系统通知渠道页面控制锁屏显示。",
            desc = "在「设置 - 界面设置 - 通知栏」底部可自定义通知控制体验：\n" +
                "1. 通知按钮：支持从「停止」「上一个」「下一个」「切换」「重置」中自由勾选最多 3 个动作按钮显示在通知展开面板上，无需打开 App 即可一键切节点或断开连接。\n" +
                "2. 通知权限与锁屏显示：点击「通知」行可查看当前通知授权状态，点击直接跳转系统应用通知管理页；在系统通知管理页内（Android 8+ 渠道级设置），你还可以按个人隐私习惯开启或关闭 VPN 状态卡片在锁屏界面上的显示。",
            notes = "通知按钮受系统面板宽度限制，最多同时选 3 个；在系统里彻底屏蔽应用通知权限可能会导致部分手机后台保活权重降低。",
            recommendation = "保留通知权限并从通知按钮中选最常用的「停止、下一个、重置/切换」3 个，日常单手操作最顺手。",
            keywords = "通知按钮 锁屏 通知权限 渠道 上一个 下一个 停止 重置 notificationActions notificationPermission",
        ),
        DocEntry(
            id = "ui_home_display_preferences",
            categoryId = "ui_notification",
            title = "主页展示元素：落地 IP、全部组标签与安全确认",
            badge = "推荐: 开启落地 IP",
            summary = "控制主页底部出口归属与 IP 探测、策略组摘要、「全部」汇总组、隐藏节点地址及底栏/触感。",
            desc = "在「设置 - 界面设置 - 配置列表 / 杂项」中可按个人喜好调校主页：\n" +
                "1. 显示主页落地 IP（推荐开启）：实时探测当前节点的公网出口 IP、国家地区与运营商类型，是识别节点真通畅、防回源直连的重要安全凭证。\n" +
                "2. 显示策略组摘要（默认开启）：直接在策略组卡片上显示成员数量与当前正在承载流量的落地节点名（例如：节点 8 · 日本 02）。\n" +
                "3. 显示「全部」组标签页（默认关闭）：在订阅标签栏最左侧增加一个汇总标签「全部」，方便跨组查看或统一检索。\n" +
                "4. 始终显示节点地址（默认关闭）：仅在排查域名/IP 问题时建议打开，截图或公开分享页面前保持关闭防泄露。\n" +
                "5. 确认删除配置（默认开启）、显示底部操作栏、触感反馈：防止单手误删配置并优化点击手感。",
            notes = "落地 IP 探测仅在连接建立后与切换落地节点时异步触发一次轻量请求，并内置 60 秒缓存，不消耗明显流量。",
            recommendation = "务必开启「显示主页落地 IP」「显示策略组摘要」和「确认删除配置」，节点总数多时按需打开「全部」标签页。",
            keywords = "落地 IP 全部组 策略组摘要 节点地址 确认删除 底部栏 触感反馈 showLandingIp showBalancerSummary showAllGroupsTab",
        ),
        DocEntry(
            id = "ui_theme_icon_scale_widget",
            categoryId = "ui_notification",
            title = "主题配色、桌面图标、界面与字体大小及桌面小组件",
            badge = "个性化定制",
            summary = "支持纯色/莫奈配色、系统跟随夜间模式、独立 UI 字体与分区域缩放、多套桌面图标及桌面快捷小组件。",
            desc = "OwnBox 提供丰富的系统级外观定制能力：\n" +
                "1. 主题与深浅色模式：支持多套纯色与动态配色主题以及深色模式跟随系统或常驻夜间护眼。\n" +
                "2. 界面与字体大小：在「界面设置」顶部可进入独立缩放控制台，不仅可整体调节界面/字体比例与选择“忽略系统超大字体”防布局错位，还支持按按钮、菜单、卡片等区域微调大小。\n" +
                "3. 桌面应用图标定制：可在图标设置中切换经典、极简、深浅纯色及自适应风格图标。\n" +
                "4. 桌面小组件：长按手机桌面添加 OwnBox 小组件，提供 1x1 极简开关、2x2 状态卡、4x1 横条与 4x2 信息面板四种规格，桌面一键开关代理与查看连接状态。",
            notes = "切换桌面应用图标后，部分厂商桌面启动器可能需要 1–2 秒刷新图标缓存；若字体显示过大被裁切，开启「忽略系统超大字体」即可恢复标准排版。",
            recommendation = "所有外观缩放与小组件对核心网络吞吐无影响，按自己眼睛舒适度与桌面风格自由选用即可。",
            keywords = "主题 夜间模式 字体大小 界面缩放 忽略系统大字体 桌面图标 小组件 widget 1x1 2x2 4x1 4x2 uiScaleSettings",
        ),

        // 5. VPN 与分应用
        DocEntry(
            id = "vpn_autoconnect_metered_lan",
            categoryId = "vpn_perapp",
            title = "自动连接、计费网络与绕过局域网",
            badge = "推荐: 开启绕过局域网",
            summary = "控制开机自启连接、向系统声明按流量计费防后台偷跑，以及访问本地路由器/NAS 不走 VPN。",
            desc = "在「设置 - VPN 设置」中的三项日常基础选项：\n" +
                "1. 自动连接（默认关闭）：手机开机完成或后台进程被重启拉起时自动连接上次选中的配置，适合主力节点长期稳定且需全天候在线的用户。\n" +
                "2. 计费网络（默认关闭）：向 Android 系统声明当前 VPN 属于计费连接，系统会自动暂停应用商店大体积自动更新与云相册全量备份，防止机场流量被后台偷跑耗光。\n" +
                "3. 绕过局域网（推荐开启）与内核绕过局域网：前者直接在系统底层 VPN 路由表将内网私有网段排除在外（零用户态拷贝开销），保证访问家用路由器后台、NAS、投屏设备保持全速直连；后者仅在系统路由排除不兼容的魔改机型上作为兼容替代方案开启。",
            notes = "机场套餐流量较小且手机默认开启系统自动云备份/应用更新的用户，强烈建议打开「计费网络」。",
            recommendation = "「绕过局域网」保持开启，「内核绕过局域网」保持默认关闭；按机场流量充裕程度决定是否开启「计费网络」。",
            keywords = "自动连接 计费网络 绕过局域网 内核绕过局域网 NAS 路由器 isAutoConnect meteredNetwork bypassLan",
        ),
        DocEntry(
            id = "vpn_strict_singtun_mtu",
            categoryId = "vpn_perapp",
            title = "严格路由模式、Sing-Tun 协议栈与 MTU 设置",
            badge = "默认: MTU 1360",
            summary = "严格路由封堵旁路泄露，全面采用 sing-box 原生 Sing-Tun 协议栈，默认 MTU 1360 兼顾隧道吞吐与稳定性。",
            desc = "VPN 虚拟网卡底层关键性能与防泄露参数说明：\n" +
                "1. 严格路由：激活强隔离路由，防止网络波动瞬间部分应用直接绕过 VPN 走物理网卡发起连接，强化防 DNS/IP 旁路泄露。\n" +
                "2. TUN 实现模式：OwnBox 已全面统一采用 sing-box 官方原生的 Sing-Tun 高性能网络协议栈，兼顾优异多路并发吞吐与更低内存占用。\n" +
                "3. 最大传输单元（MTU）：程序内置默认值为 1360（设置项中亦提供 1400 / 1500 / 9000 等常见值）。由于外层代理隧道（如 TLS、WireGuard、QUIC）会占用几十字节包头，默认 1360 或 1400 相比 1500/9000 更能有效避免弱网和运营商蜂窝链路下的分片黑洞与网页卡死。",
            notes = "除非确认链路完全无分片黑洞且追求极限大包测试，一般不建议随意调高至 9000；若某条移动蜂窝网络下图片半截加载卡住，尝试将 MTU 微调至 1280–1400。",
            recommendation = "保持标准 Sing-Tun 协议栈与 MTU 默认值 1360（或 1400），若出现极个别应用切换多卡网络慢可排查严格路由状态。",
            keywords = "严格路由 sing-tun mtu 1360 1400 1500 分片黑洞 协议栈 strictRoute tunImplementation",
        ),
        DocEntry(
            id = "vpn_mode_inbound_auth_sharing",
            categoryId = "vpn_perapp",
            title = "服务模式、混合入站、认证与免 Root 局域网共享",
            badge = "默认: VPN + 端口 2080",
            summary = "日常用 VPN 模式全自动接管流量，本机混合 HTTP/SOCKS5 默认端口 2080，免 Root 即可通过热点或同 Wi-Fi 共享代理。",
            desc = "在「设置 - 模式与入站设置」中管理应用运行形态与本地入口：\n" +
                "1. 服务模式：日常标准选用「VPN 模式」开箱即用；「仅代理模式」只启动内核开放本地端口，不拉起系统 VPN，需配合其他设备或手动设置代理。\n" +
                "2. 混合入站端口（默认 2080）与禁用开关：本机默认同时在 127.0.0.1:2080 监听 HTTP 与 SOCKS5 混合代理。若 2080 与本机其他工具碰撞，改为其他空闲端口即可；极端环境可「禁用混合入站」退回纯 TUN 监听。\n" +
                "3. 混合入站认证与 HTTP 绕过名单：允许局域网接入时建议设置用户名/密码防止蹭网；HTTP 代理绕过名单默认涵盖本地回环与内网段。\n" +
                "4. 局域网共享（免 Root）：开启后绑定 0.0.0.0 并给出清晰的手机热点 IP（如 192.168.43.1）与当前 Wi-Fi IP，同网段电脑、Switch、PS5 只需把 Wi-Fi 的 IP 设置保持为【DHCP】，在【代理】里选择【手动】填入主机名和 2080 端口即可免 Root 共享科学上网。",
            notes = "配置其他设备接入手机共享热点时，切勿改动目标设备的“IP 设置（必须保持 DHCP）”和网关，只修改下方的手动 HTTP 代理主机名与端口。",
            recommendation = "日常保持 VPN 模式和 2080 端口；给 Switch/电脑/另一台手机共享时直接在工具或模式设置中点开「局域网共享」按引导操作，用完及时关闭。",
            keywords = "服务模式 混合入站 2080 禁用混合入站 认证 密码 绕过名单 局域网共享 热点 switch ps5 lanSharing mixedPort",
        ),
        DocEntry(
            id = "perapp_principle_and_list",
            categoryId = "vpn_perapp",
            title = "分应用代理底层原理与黑白名单选型",
            badge = "推荐: 按需白/黑名单",
            summary = "在 Linux 套接字层按包名将应用排除在 VPN 之外，微信、网银等纯物理直连零延迟不风控。",
            desc = "Android 系统的 VpnService 支持按应用 UID 与包名进行底层流量隔离：\n" +
                "1. 绕过模式（黑名单）：勾选微信、支付宝、银行、国内视频与游戏等国产应用，使它们完全不经过 VPN 虚拟网卡直接走物理 Wi-Fi/5G，既全速又避免触发异地 IP 登录风控。\n" +
                "2. 仅代理模式（白名单）：只勾选确实需要加速的海外应用，其余系统与国内 App 全部走直连，最省电且互不干扰。\n" +
                "3. 一键筛选常用海外应用：界面内提供便捷按钮，辅助快速选出已安装的常用国际软件。",
            notes = "切换白名单模式时若漏掉关键依赖组件（例如浏览器内核或系统下载管理器），可能导致对应应用的下载或内嵌网页走直连失败。",
            recommendation = "海外软件不多时推荐「白名单（仅代理所选应用）」最清爽；如果海外软件很多只想排除几个国内银行和游戏，选「黑名单（绕过所选应用）」。",
            keywords = "分应用代理 白名单 黑名单 绕过 微信 支付宝 网银 银行 套接字 proxyApps",
        ),
        DocEntry(
            id = "perapp_google_play_solution",
            categoryId = "vpn_perapp",
            title = "为什么只选 Google Play 仍卡 0% 及智能联动方案",
            badge = "必读痛点解析",
            summary = "Play 商店实际委托系统下载管理器拉取安装包；OwnBox 勾选 Play 商店时自动联动下载服务解决下载卡死。",
            desc = "很多用户在开启分应用代理白名单时，只勾选了「Google Play 商店 (com.android.vending)」，结果能正常搜索、看评论，但一点「安装/更新」就永远卡在 0% 或等待下载。\n" +
                "【真实原因】：Google Play 商店本体只负责 UI 展示与鉴权，真正建立大文件 HTTP 下载连接的是 Android 系统组件「下载管理器 (com.android.providers.downloads)」和 Google 框架服务（com.google.android.gms / com.google.android.gsf）。白名单只勾商店本体时，下载管理器发起的真实下载会被系统强制拦在 VPN 外面直连国内而失败。\n" +
                "【OwnBox 智能解决】：当白名单中包含 Google Play 商店时，OwnBox 底层会自动联动把系统下载管理器（含小米系统下载组件）和 GMS/GSF 纳入 VPN 通道，再通过内置的 Google Play 专线域名规则将商店 API 与安装包 CDN 精准导往代理节点，同时不影响下载管理器帮国内软件走直连。",
            notes = "若你自己手动修改了路由规则删除或覆盖了内置 Google 规则，请留意不要误把 gvt1.com 或 googleapis.cn 等域名放进直连规则。",
            recommendation = "在分应用白名单中勾选 Google Play 时，保持默认智能路由规则即可实现“Play 秒下、国内不耗代理流量”。",
            keywords = "google play 商店 0% 下载 卡住 下载管理器 gms gsf vending gvt1 分应用 联动",
        ),

        // 6. 核心与路由
        DocEntry(
            id = "core_smart_route_and_sniff",
            categoryId = "core_routing",
            title = "智能分流国家/地区与流量嗅探",
            badge = "推荐: CN + 开启嗅探",
            summary = "可切换本地直连所属国家规则基准；流量嗅探自动从纯 IP 请求中提取 SNI/Host 域名大幅提升规则命中率。",
            desc = "在「设置 - 核心设置」顶部有两项直接决定分流精准度的引擎开关：\n" +
                "1. 智能分流国家/地区（默认 中国大陆 CN）：用于确定智能分流模式下哪些本地站点与本地 IP 段默认走直连放行，可根据自身所在或适配需要切换国家基准。\n" +
                "2. 流量嗅探（默认开启）：深入识别流经代理网卡的连接首包中的 TLS SNI、HTTP Host 及 QUIC 域名字段。即使部分应用先自行解析出 IP 再发起纯 IP 连接，内核依然能还原出真实域名去精准匹配域名分流规则。",
            notes = "如果关闭流量嗅探，所有基于 GeoSite 与域名后缀的智能分流规则在遇到纯 IP 直连拨号的 App 时都会失效掉进兜底出站。",
            recommendation = "保持「流量嗅探」开启，境内用户将「智能分流国家/地区」保持默认中国大陆（CN）。",
            keywords = "智能分流 国家 地区 CN 流量嗅探 SNI Host QUIC smartRouteCountry trafficSniffing",
        ),
        DocEntry(
            id = "core_quic_and_resolve",
            categoryId = "core_routing",
            title = "阻断 QUIC 与解析目标地址",
            badge = "推荐: 开阻断 QUIC / 关解析目标地址",
            summary = "阻断 QUIC 强制网页视频走高稳定 TCP 避免 UDP 限速；解析目标地址与默认开启的 FakeDNS 冲突，日常建议关闭。",
            desc = "两项关键的协议与解析协同设置：\n" +
                "1. 阻断 QUIC（默认开启）：对 UDP 443 端口的 QUIC 流量执行快速拦截拒绝，促使 Chrome、YouTube、Instagram 等客户端自动平滑降级使用稳定的 TLS/HTTP2 TCP 协议传输。由于部分物理宽带和机场中转对大流量 UDP QOS 限速极严，阻断 QUIC 后视频加载和网页开屏往往明显更稳。\n" +
                "2. 解析目标地址（默认关闭）：开启后会要求内核在路由判断前先把域名在本机解析成真实 IP 再转发。这与默认开启的 FakeDNS（让远端节点就近解析域名以实现 0ms 首包并拿最优 CDN）正好相反且会相互冲突。",
            notes = "只有当你明确在「DNS 设置」里关闭了 FakeDNS，且依赖纯 GeoIP / IP 段规则分流，或者想在本地提前按 IPv6 策略固定解析结果时，才需要开启「解析目标地址」；少数强依赖 HTTP/3 QUIC 的冷门场景可临时关闭「阻断 QUIC」排查。",
            recommendation = "日常强烈建议保持「阻断 QUIC」开启，并配合默认开启的 FakeDNS 保持「解析目标地址」关闭。",
            keywords = "阻断 QUIC UDP 443 降级 TCP 解析目标地址 FakeDNS 冲突 GeoIP blockQuic resolveDestination",
        ),
        DocEntry(
            id = "core_ipv6_mode",
            categoryId = "core_routing",
            title = "IPv6 路由模式与双栈自适应保护",
            badge = "默认: 禁用 IPv6",
            summary = "全模式适配不同宽带与 5G 环境：禁用保护本机防泄露，启用/优先在无 IPv6 宽带下自动回退 IPv4。",
            desc = "在「设置 - 核心设置 - IPv6」中提供禁用、启用、优先及仅 IPv6 四种模式：\n" +
                "1. 禁用（默认且最稳定）：在手机端不在系统 VPN 虚拟网卡声明 IPv6 地址，并在路由与 DNS 层拦截本机 IPv6 流量与 AAAA 查询，从根源消除微信等双栈 App 超时转圈并防止本机真实 IPv6 泄露。注：开启 FakeDNS 时，走代理的域名由远程双栈节点代为请求，测速网站仍可能检测出节点自己的 IPv6，这属于正常现象，并非本机泄露。\n" +
                "2. 启用（IPv4 优先）与 优先（IPv6 优先）：实时检测当前默认物理网络（Wi-Fi 或 5G）的公网 IPv6 连通性。如果本地宽带不支持 IPv6 或光猫仅分配假 IPv6，直连域名解析与出站会自动透明降级并回退至 IPv4，彻底避免国内双栈网站无法访问，且在 Wi-Fi 与 5G 切换时自动刷新缓存无需重启代理。\n" +
                "3. 仅 IPv6：严格纯 IPv6 模式，仅适用于本地物理网络和代理节点入口均完整具备可用 IPv6 的专线环境，日常双栈网络请勿选用。",
            notes = "OwnBox 已保证节点测速严格沿用当前选定的 IPv6 模式；如确实不希望在「禁用」模式下被测速网站检测到节点的 IPv6，可在 DNS 设置中关闭 FakeDNS。",
            recommendation = "绝大多数日常用户保持默认「禁用」即可；有访问纯 IPv6 站点或同时使用双栈网络需求时推荐选「启用」或「优先」。",
            keywords = "IPv6 IPv4 禁用 启用 优先 仅 双栈 回退 泄露 AAAA 微信 转圈 节点 IPv6 ipv6Mode",
        ),
        DocEntry(
            id = "core_dual_net_and_concurrent",
            categoryId = "core_routing",
            title = "双网络加速与并发连接建立",
            badge = "默认: 均关闭",
            summary = "双网络加速允许同时调动 Wi-Fi 与蜂窝并发；并发连接同时尝试多路建链，默认关闭以防耗流与机场风控。",
            desc = "两项高负载实验性连接加速选项均放在「设置 - 核心设置」中：\n" +
                "1. 双网络加速（默认关闭）：允许同时使用 Wi-Fi 和移动数据两个物理接口发包，弱 Wi-Fi 环境下能抗抖动，但会持续消耗手机蜂窝流量，且双出口 IP 并发可能触发部分机场多地同时在线封号限制。\n" +
                "2. 并发连接建立（默认关闭）：拨号节点时同时发起双栈多路连接竞争取最快一路，轻微加快冷连接但会使节点端建链请求压力翻倍。",
            notes = "对机场节点并发连接数限制严格的用户，开启并发连接建立或双网络加速更容易被面板判定为高频异常连接。",
            recommendation = "日常保持默认关闭；单接口按普通方式建链最省电、最不易触及节点限流与封号阈值。",
            keywords = "双网络加速 多路径 并发连接建立 蜂窝 Wi-Fi 风控 限流 dualNetworkAcceleration concurrentDial",
        ),
        DocEntry(
            id = "core_ruleset_srs",
            categoryId = "core_routing",
            title = "二进制规则集 (.srs) 与规则更新源",
            badge = "推荐: 官方默认源",
            summary = "使用 sing-box 高性能编译型二进制规则库 (.srs)，支持配置 Geosite/GeoIP 下载地址及手动/定时更新。",
            desc = "OwnBox 的分流路由采用 sing-box 编译型 .srs 二进制规则集引擎：\n" +
                "1. 高效低耗：把数万条国内外域名和 IP 段编译成紧凑索引结构，打开连接秒级加载、常驻内存占用极低。\n" +
                "2. 规则来源与 URL：在核心设置最下方可切换规则集提供方（或自定义 Geosite / GeoIP 下载 URL）与定时更新间隔；若默认 GitHub 下载慢，可开启代理后更新或替换镜像下载链接。",
            notes = "把自动更新间隔设为过短会导致在后台反复拉取几十 MB 规则数据库浪费流量；设为 0 则完全按需手动在菜单或设置中点更新。",
            recommendation = "保持官方规则源并将自动更新间隔维持按需手动（每隔一两个月连着代理更新一次即可）。",
            keywords = "规则集 srs geosite geoip 规则更新 间隔 下载源 rulesProvider rulesGeositeUrl rulesGeoipUrl",
        ),
        DocEntry(
            id = "routing_match_order_and_logic",
            categoryId = "core_routing",
            title = "自定义路由自上而下匹配原则、模式提示与失效告警",
            badge = "核心必懂原理",
            summary = "规则从上往下先命中先停，同类条件 OR、跨维条件 AND；路由页顶部实时提示当前模式并对失效节点标红告警。",
            desc = "要写出不冲突、不漏流的分流规则，需掌握以下机制：\n" +
                "1. 自上而下、先命中先执行：流量进入核心后会从第 1 条规则顺着往下扫描，只要完全命中其中 1 条，立即执行该条对应的出站或动作，后面的规则全部跳过不再比对。全部未命中则落入最底部的默认出站。\n" +
                "2. 单条规则内的组合逻辑：\n" +
                "   - 主体条件之间是 OR（或）：在同一条规则中填写多个域名、多个 IP 段或多个应用包名时，命中其中任意一个即算主体满足。\n" +
                "   - 约束维度之间是 AND（且）：若同一条规则还填了目标端口（Port）或传输协议（TCP/UDP），则必须既命中主体目标、又满足端口和协议才算整条命中。\n" +
                "3. 模式提示横幅与失效出站保护：当侧边栏处于「智能分流」或「全局代理」时，路由页面顶部会展示状态提示横幅并提供一键切回自定义路由入口；若某条规则指向的节点因删改或更新订阅已不存在，列表会标红高亮并在启动时弹窗提醒，防止静默误走主代理。",
            notes = "如果把宽泛的“全匹配放行”规则排在最前面，排在它下方的任何精细规则都会变成死规则永远不生效。",
            recommendation = "排布规则始终遵循“广告阻断与特例域名在最顶 -> 特定服务与业务在中间 -> 大范围直连与兜底规则放最底”，看到标红规则及时重新指定节点。",
            keywords = "路由 规则 匹配 顺序 优先级 OR AND 逻辑 端口 协议 域名 横幅 无效出站 标红 route",
        ),
        DocEntry(
            id = "protocol_mtls_and_hy2",
            categoryId = "core_routing",
            title = "协议进阶：AnyTLS 双向认证与 Hysteria2 稳定性调节",
            badge = "节点协议调优",
            summary = "AnyTLS 支持客户端双向认证 (mTLS)；Hysteria2 锁定默认物理网卡防 Wi-Fi 抢跑并提供禁用路径 MTU 发现开关。",
            desc = "针对两类高频现代协议的专项支持：\n" +
                "1. AnyTLS 客户端双向认证 (mTLS)：在 AnyTLS 节点编辑页「安全设置」中支持填写「客户端证书」与「客户端私钥」（可直接粘贴 PEM 多行文本或填写本地文件路径），并打通 JSON 出站与 Clash YAML 订阅导入。\n" +
                "2. Hysteria2 / TUIC Wi-Fi 稳定性与 MTU 探测控制：底层已自动锁定 QUIC/UDP 代理协议始终绑定当前默认物理网卡，并在切换 Wi-Fi / 移动网络时自动重建失效会话；若在部分存在路径 MTU 黑洞的宽带下出现高丢包或断流，可在 Hysteria2 编辑页打开「禁用路径最大传输单元发现」。",
            notes = "「禁用路径最大传输单元发现」仅在特定宽带或校园网 UDP 分片受阻时按需打开，正常环境下保持默认关闭即可。",
            recommendation = "常规机场节点保持默认参数即可；有服务端强校验客户端证书的 AnyTLS 节点可直接在安全设置填入 PEM 证书与私钥。",
            keywords = "AnyTLS mTLS 双向认证 客户端证书 私钥 Hysteria2 TUIC WiFi 抢跑 禁用路径 MTU 发现 disableMtuDiscovery",
        ),
        DocEntry(
            id = "routing_actions_and_re2_editor",
            categoryId = "core_routing",
            title = "规则动作类型与高级规则编辑器 Go RE2 校验",
            badge = "进阶规则编辑",
            summary = "支持 route、reject (reply/drop)、hijack-dns 等动作，编辑器自带 Go RE2 正则实时合法性检测。",
            desc = "OwnBox 高级路由编辑器支持卡片表单与 JSON 视图联动，在配置动作与正则时具备完整防护：\n" +
                "1. 动作类型说明：\n" +
                "   - route：导向指定的代理节点、策略组、直连（direct）或阻断。\n" +
                "   - reject（reply vs drop）：拦截广告或危险请求时，推荐选 reply（立即回送 TCP RST / ICMP 拒绝报文让 App 秒级放弃重试，不费电不转圈）；若防端口探测可选静默 drop 丢弃不回响应。\n" +
                "   - hijack-dns / resolve：无缝将 53 端口明文 DNS 请求劫持进核心 DNS 引擎统一防污染处理。\n" +
                "2. Go RE2 正则实时校验：sing-box 采用不支持回溯前瞻语法（如 (?=...)、(?<=...)）的 Go RE2 引擎；输入 domain_regex 时编辑器实时检测语法合法性并即时高亮提示错误，彻底避免启动内核 Panic 报错。",
            notes = "常规域名优先使用 domain_suffix（后缀）或 domain_keyword（关键词）匹配，性能远高于正则，只有复杂格式才需用 domain_regex。",
            recommendation = "广告拦截规则建议动作设为 reject (reply) 加速页面渲染；写入正则时确认编辑器提示绿色通过后再保存。",
            keywords = "高级编辑器 action reject drop reply hijack-dns 正则 RE2 domain_regex 报错 panic",
        ),

        // 7. DNS 与测速
        DocEntry(
            id = "dns_remote_and_direct",
            categoryId = "dns_speedtest",
            title = "远程加密 DNS、国内直连 DNS 与快速预设选择",
            badge = "默认: Google + 阿里 DoH",
            summary = "海外走 Google DoH 防污染，国内走阿里 DoH 就近解析，内置 DoH/DoQ/DoH3 快速预设一键填入。",
            desc = "在「设置 - DNS 设置」中明确划分了国内外两条 DNS 解析路径：\n" +
                "1. 远程 DNS（默认 https://dns.google/dns-query）：经过主代理出站通道向海外发起加密解析，专门解析海外被污染或仅限海外访问的域名，严禁设为本机 local（内核会自动防护回退，避免本地 DNS 泄露）。\n" +
                "2. 直连 DNS（默认 https://223.5.5.5/dns-query）：不经过代理直接由本地网络解析国内站点，保证返回距离本地宽带最近的国内视频、网盘 CDN 服务器 IP。\n" +
                "3. 快速预设选择器：在远程 DNS 与直连 DNS 下方均提供快速预设列表（包含 Google/Cloudflare DoH、Cloudflare HTTP/3 (DoH3)、AdGuard/阿里 DoQ、腾讯 DNSPod 等），点击即可一键填入免手输。\n" +
                "4. ECH 节点公钥直连放行：若节点启用了 ECH，核心会自动放行代理节点自身 ECH 记录走直连加密 DNS 解析并缓存，不影响正常网页加速。",
            notes = "切勿将直连 DNS 改成海外 DNS（例如 8.8.8.8），否则微信、抖音、淘宝、B站等国内软件会解析出境外或跨网慢速服务器导致明显卡顿。",
            recommendation = "普通用户保持默认的 Google DoH（远程）与阿里 223.5.5.5 DoH（直连）即是目前速度与防污染最佳黄金组合；需要更换时优先从「快速预设」选择。",
            keywords = "远程DNS 直连DNS 快速预设 DoH DoQ DoH3 ECH remoteDns directDns 223.5.5.5 dns.google 污染",
        ),
        DocEntry(
            id = "dns_domain_strategy",
            categoryId = "dns_speedtest",
            title = "分维度域名解析策略：远程、直连与服务器解析",
            badge = "默认: auto 自动",
            summary = "可分别为远程 DNS、直连 DNS 以及节点自身服务器域名指定 IPv4/IPv6 解析偏好，默认全部遵循全局模式。",
            desc = "在「设置 - DNS 设置」中可分别为三类域名配置解析策略（auto / prefer_ipv4 / prefer_ipv6 / ipv4_only / ipv6_only）：\n" +
                "1. 远程域名解析策略：控制海外域名的 A/AAAA 解析偏向。\n" +
                "2. 直连域名解析策略：控制国内直连域名的解析偏向。\n" +
                "3. 节点服务器解析策略：单独控制翻墙节点本身的服务器域名如何解析，便于节点仅支持 IPv4 或需优先 IPv6 直连入口时精准调优。\n" +
                "设为 auto 时会自动与核心设置里的 IPv6 模式保持一致。",
            notes = "例如本地物理网络 IPv6 正常但节点落地只有 IPv4 时，保持核心 IPv6 禁用或将各维度保留为 auto 最不容易出问题。",
            recommendation = "无特殊双栈调试需求时，三项解析策略全部保持默认 auto，统一由核心「IPv6 模式」协调管理。",
            keywords = "域名解析策略 domain_strategy_for_remote domain_strategy_for_direct domain_strategy_for_server auto",
        ),
        DocEntry(
            id = "dns_routing_fakedns_hosts",
            categoryId = "dns_speedtest",
            title = "启用 DNS 分流路由、FakeDNS 与静态 Hosts",
            badge = "推荐: 开启分流与 FakeDNS",
            summary = "DNS 分流隔离国内外解析；FakeDNS 以 198.18.0.0/15 假 IP 实现 0 毫秒海外域名秒开并根绝本地泄漏。",
            desc = "DNS 核心解析加速与隐私防漏机制：\n" +
                "1. 启用 DNS 分流路由（默认开启）：自动辨别海外与国内域名分送不同 DNS 服务器，配合默认内置专线规则确保 Google Play 等服务不被国产域名误分流。\n" +
                "2. 启用 FakeDNS（默认开启）：针对海外域名立即由内核返回一个 198.18.0.0/15 的保留虚拟 IP，应用零等待直接向内核发起 TCP 建链，真正由远端代理节点去就近解析真实 IP——实现 DNS 解析 0-RTT 秒开并从物理上 100% 消除本地海外 DNS 泄露。\n" +
                "3. 本地静态 Hosts：仅在需要强行固定某个私有或特定域名解析 IP 时手动配置，常规使用留空即可。",
            notes = "若在路由器层面也跑了带 FakeIP 的旁路由透明代理，双层 FakeIP 可能造成嵌套冲突；此时可关闭路由器代理或临时关闭手机端 FakeDNS 排查。",
            recommendation = "手机直接连接家用普通 Wi-Fi 和 5G 流量时，强烈推荐同时开启「DNS 分流路由」与「FakeDNS」。",
            keywords = "DNS分流 FakeDNS 假IP 0-RTT Hosts 198.18 enableDnsRouting enableFakeDns dnsHosts",
        ),
        DocEntry(
            id = "tls_fragment_antiblock",
            categoryId = "dns_speedtest",
            title = "TLS 分片抗 SNI 阻断配置",
            badge = "默认: 关闭 (按需开启)",
            summary = "启用核心原生 TLS Client Hello 分片与回退发包间隔（1~1000ms），拯救被 SNI 阻断超时的节点。",
            desc = "当使用某些直连域名节点的 TLS 握手阶段，出现“TCP 端口可以接通但 TLS 握手必超时或直接被 Reset 重置”，在「设置 - 分片设置」可启用抗阻断：\n" +
                "1. 启用 TLS 分片（默认关闭）：启用内核原生 TLS 分片发送，打乱防火墙 SNI 重组检测特征；分片长度现由内核自动协商，无需人工猜测包长。\n" +
                "2. 分片间隔（默认 10-20 毫秒）：严格校验有效区间为 1~1000 毫秒，控制分片回退触发延迟。",
            notes = "正常直通可用的中转机场与未遭 SNI 阻断的节点不需要开启 TLS 分片；开启后每次 TLS 握手会额外多出十几毫秒拆分等待。",
            recommendation = "保持默认关闭；仅在确认节点能 ping 通端口却频繁报 TLS handshake timeout 时开启验证。",
            keywords = "TLS分片 fragment SNI 阻断 超时 reset enableTLSFragment fragmentInterval 1000ms",
        ),
        DocEntry(
            id = "observatory_speedtest_params",
            categoryId = "dns_speedtest",
            title = "连通性测试 URL、测速模式、超时与隐藏不可用节点",
            badge = "默认: 204 / 简单下载 / 8s",
            summary = "连通性默认 Cloudflare 204（3 秒超时），带宽测速默认简单下载与 8000ms 超时；自动隐藏不可用默认关闭。",
            desc = "在主页测速与「设置 - 连接观测与负载均衡」中控制连通性与带宽测速参数：\n" +
                "1. 连通性测试 URL（默认 https://cp.cloudflare.com/generate_204）：响应无内容 204 状态码、几乎零流量消耗，单节点延迟测试超时默认为 3000 毫秒。\n" +
                "2. 速度测试模式（默认 简单下载）：可在「下载和上传 / 下载 / 上传 / 简单下载」间切换，通过真实代理通道测量节点带宽速率；支持自定义「简单下载 URL」。\n" +
                "3. 速度测试超时时间（默认 8000 ms）：单个节点进行带宽速度测试的上限时间。\n" +
                "4. 自动隐藏不可用节点（默认关闭）：建议保持关闭，防止某次网络波动测失败后节点从界面消失让你找不到节点。",
            notes = "切勿把「连通性测试 URL」填成几百 MB 的大文件链接，否则批量测延迟和策略组周期检查会迅速刷光机场流量。",
            recommendation = "连通性 URL 保持默认 204、带宽测速保持默认「简单下载」与 8000 ms 超时、关闭自动隐藏不可用节点。",
            keywords = "测速URL 204 简单下载 超时 8000ms 3000ms 隐藏不可用节点 connectionTestURL speedTestMode speedTestTimeoutMs hideUnavailableProfiles",
        ),

        // 8. 进阶与工具
        DocEntry(
            id = "adv_smart_auto_stop",
            categoryId = "advanced_tools",
            title = "智能启停代理服务",
            badge = "场景化省电 · 无需定位",
            summary = "无需位置权限，按本地 IP / CIDR / 网关规则自动识别信任内网，连入时挂起代理，离开后自动恢复。",
            desc = "在「设置 - 进阶设置 - 高级网络」中支持配置「智能启停代理服务」：\n" +
                "1. 底层内网匹配（无需定位权限）：支持配置最多 5 条本地 IP、CIDR 网段或 gateway:网关 IP 规则（如 192.168.1.0/24, gateway:192.168.10.1），直接读取物理网卡链路属性判定，不申请 GPS/定位权限。\n" +
                "2. 平滑挂起与通知控制：连入命中网段的信任路由器或软路由网络时，自动将核心平滑挂起为「已挂起」状态并在通知栏提供「立即恢复」与「停止」按钮。\n" +
                "3. 离开自动恢复：一旦断开信任内网切换回 5G 或公共 Wi-Fi，立即自动恢复 OwnBox VPN 代理（在同一个信任网络下手动点“立即恢复”后不会反复重新挂起）。",
            notes = "可彻底解决“家里已有旁路由/软路由代理，手机回家后双层套娃冲突”问题，同时节省电量。",
            recommendation = "家里或公司网络已自带科学路由的用户建议开启智能启停并配置内网网段或 gateway 规则；普通网络用户保持关闭即可。",
            keywords = "智能启停 内网 自动挂起 自动恢复 软路由 CIDR gateway ip 无需位置权限 smartAutoStop smartAutoStopNetworks",
        ),
        DocEntry(
            id = "adv_network_change_wake_lock",
            categoryId = "advanced_tools",
            title = "网络切换连接重置、唤醒重置与后台唤醒锁",
            badge = "默认: 关闭重置 + 独立复位 DNS",
            summary = "网络切换默认保留已有长连接同时主动重置 DNS 连接池，防止聊天下载中断也防 DNS 卡死；唤醒锁按需保活。",
            desc = "连接跨网络稳定性保障三项开关（设置 - 进阶设置）：\n" +
                "1. 网络变化时重置出站连接（默认关闭）：关闭时尽量不打断你正在下载的文件、直播或通话等既有长连接；但当底层检测到物理网卡变动时，OwnBox 仍会在后台主动刷新路由器 DNS/HTTP 连接池，防止 DoH 连接挂在旧网卡上失联。如果你经常遇到出门从 Wi-Fi 切到 5G 后应用明显卡住转圈，可开启该项让其立即彻底重置全部旧连接。\n" +
                "2. 唤醒时重置连接：手机长时间锁屏休眠后亮屏瞬间刷新可能已被基站超时的长连接，解决亮屏后聊天消息延迟。\n" +
                "3. 后台唤醒锁（默认关闭）：开启后会申请 PARTIAL_WAKE_LOCK 避免 CPU 深度休眠断网，仅建议在极端杀后台机型上开启。",
            notes = "开启后台唤醒锁会轻微提高手机夜间待机耗电量；普通机型优先把 OwnBox 设为系统电池策略“无限制”即可。",
            recommendation = "切网体验不畅的用户可开启「网络变化时重置出站连接」与「唤醒时重置连接」；「后台唤醒锁」仅遇到熄屏掉网问题时再打开。",
            keywords = "网络变化重置连接 唤醒重置 唤醒锁 保活 息屏 切网 假死 networkChangeResetConnections wakeResetConnections acquireWakeLock",
        ),
        DocEntry(
            id = "adv_security_perf_debug",
            categoryId = "advanced_tools",
            title = "安全校验、最低 TLS、性能模式、日志级别与重置设置",
            badge = "日常用 none / warn",
            summary = "日常禁用全局不安全证书并保持最低 TLS 1.2；日志级别默认 none，排查可临时开 warn，切忌常驻 debug/trace。",
            desc = "在「设置 - 进阶设置」中管理安全、性能与排查参数：\n" +
                "1. 安全选项：「全局允许不安全证书」建议始终保持关闭（有自签名节点仅应在单个节点配置中单独勾）；「请求时允许不安全」仅用于访问自签名订阅链接；「最低 TLS 版本」默认 1.2 拥有最高节点兼容率。\n" +
                "2. 性能与隐私：「性能优先模式」针对高吞吐场景优化内存回收阈值（建议默认关闭）；「从最近任务隐藏」使 OwnBox 不出现在系统多任务卡片列表中。\n" +
                "3. 调试与恢复：「日志级别」提供 none / warn / info / debug / trace 五档（默认 none 仅记致命异常，排查建议临时设 warn；开启 debug/trace 会写出海量日志拖垮网速并耗电）；「全局自定义配置」供熟悉 sing-box JSON 语法的用户合并底层参数；长按「日志级别」可调日志缓存大小；配置紊乱时可点「清理缓存」或「重置设置」（仅恢复默认选项，绝不删除节点与订阅）。",
            notes = "请勿长时间开启 debug 或 trace 日志级别后再忘记关掉，大流量看 4K 视频时会产生数十兆滚动写入严重耗电发热。",
            recommendation = "安全校验保持严格默认，日常日志保持 none（排查问题临时选 warn）；遇到配置调乱导致怎么都不通时，使用「重置设置」是最快一键自愈手段。",
            keywords = "安全 不安全证书 TLS 1.2 1.3 性能优先 最近任务 日志级别 none warn debug 自定义配置 清理缓存 重置设置",
        ),
        DocEntry(
            id = "tool_connections_monitor",
            categoryId = "advanced_tools",
            title = "侧边栏「分流连接」实时监控与一键建规",
            badge = "推荐: 按需开启开关",
            summary = "无需开启 Clash API，在「设置 - 进阶设置」启用开关即可查看活跃/历史连接、实时网速并从连接详情一键创建分流规则。",
            desc = "在「设置 - 进阶设置」开启「启用分流连接」（默认关闭，开启后侧边栏立即出现「分流连接」入口）：\n" +
                "1. 无需 Clash API、零闲置耗电：直接由内核提供连接跟踪数据；开关关闭时不产生任何后台轮询与内存开销。\n" +
                "2. 活跃 / 历史双视图与实时网速：可切换查看当前活跃连接与最近 300 条已关闭历史连接（专治短连接闪断抓不到），顶部统计累计流量与实时上传/下载速率，列表展示每条连接的所属应用、命中规则、代理链路与实时速率。\n" +
                "3. 暂停、排序、筛选与批量断开：支持一键暂停定格画面，按时间/上下行速率/流量/主机名排序，按代理/直连/TCP/UDP 及策略组出站胶囊筛选，支持正则表达式搜索以及仅断开当前筛选结果。\n" +
                "4. 连接详情与一键创建分流规则：点击任意连接弹出「概览 / 代理 / 原始数据」三页签详情框，可按需联网查询目标 IP 归属地与 ASN、可视化查看代理链路、复制 JSON 原始数据，底部点击「创建规则」按域名、目标 IP 或所属应用直接预填分流规则。",
            notes = "若平时无需排查单条连接走向，建议保持「启用分流连接」关闭，既清爽又省电；查看特定应用是否走对代理时再临时开启即可。",
            recommendation = "排查“某 App/某域名到底走了直连还是代理”时，直接开启「启用分流连接」并在详情页一键创建修正规则，比翻纯文本日志高效得多。",
            keywords = "分流连接 活跃 历史连接 300条 网速 暂停 筛选 正则 连接详情 归属 ASN 一键建规 创建规则 enableConnectionsMonitor",
        ),
        DocEntry(
            id = "tool_network_diagnostics",
            categoryId = "advanced_tools",
            title = "网络诊断页：落地 IP 风险评估与流媒体/AI 解锁检测",
            badge = "实用诊断中心",
            summary = "侧边栏一键进入诊断当前落地 IP 纯净度、机房/住宅类型及 ChatGPT、Gemini、Netflix 等流媒体解锁。",
            desc = "点击侧边栏「网络诊断」即可对当前连接的代理出站做全面质量体检：\n" +
                "1. 落地 IP 与风险评估：实时抓取出口 IP、所在国家/城市、ASN 运营商，并分析是否为原生住宅 IP、商业或数据中心机房及风险评分，帮助避开容易触发验证码的高风控 IP。\n" +
                "2. 分类解锁检测：支持按 AI、流媒体、社区平台、游戏开发、金融加密分组一键并发测试 ChatGPT、Gemini、Claude、Netflix、YouTube、Disney+、Reddit 等平台实际可用性与解锁地区。\n" +
                "3. 常用平台置顶与自定义：支持在诊断页顶部调节置顶你最关心的检测平台，优先出结果。",
            notes = "检测依赖从当前运行着的 VPN 通道发起目标探针请求，因此请先开启 OwnBox 代理连接后再运行诊断；部分平台解锁取决于节点落地服务器自身出口状态。",
            recommendation = "每当换用新机场或准备登录重要 AI / 海外账号前，建议先点开「网络诊断」确认出口 IP 归属与风险状态。",
            keywords = "网络诊断 落地 IP 风险 住宅 机房 AI 解锁 流媒体 ChatGPT Gemini Netflix CloudFlare",
        ),
        DocEntry(
            id = "tool_singbox_dashboard",
            categoryId = "advanced_tools",
            title = "Sing-box 实时仪表盘：Clash API 与多面板免密直连",
            badge = "推荐: Zashboard",
            summary = "需在进阶设置开启「启用 Clash API」，侧边栏进入后可一键使用 Zashboard、内置 Yacd 或 MetaCubeXD。",
            desc = "OwnBox 完整打通标准 Clash API（默认监听 127.0.0.1:9090）提供实时流量与规则透视仪表盘：\n" +
                "1. 启用入口：先前往「设置 - 进阶设置」打开「启用 Clash API」（连上代理后从左侧边栏即可打开仪表盘）。\n" +
                "2. 多面板原生免密注入：支持直接切换现代化界面 Zashboard、纯离线内置 Yacd、极客向 MetaCubeXD 或输入自定义 Dashboard URL（保存并应用）。打开自带面板时 OwnBox 自动注入本地 API 地址与密钥直连。\n" +
                "3. 核心能力与排查：可查看实时上传/下载折线图、内核内存、每条正在进行的 TCP/UDP 连接命中了哪条分流规则和哪条节点，以及一键断开死连接。\n" +
                "   若提示无法连接，请按顺序核对：1）VPN 已连接；2）已开启 Clash API；3）9090 端口未被其他同类软件霸占；4）如曾修改开关需断连后重连一次 VPN 生效。",
            notes = "离开仪表盘页面后后台 Web 轮询自动停止，完全不消耗日常待机电量；自定义 URL 请仅导入自己信任的本地 NAS 或官方开源项目页面。",
            recommendation = "日常推荐常驻启用 Clash API 并使用 Zashboard 面板查看连接走向，排查为何某个软件没走代理一目了然。",
            keywords = "仪表盘 仪表板 Clash API 9090 Zashboard Yacd MetaCubeXD 连接 规则 内存 排查 connection refused",
        ),
        DocEntry(
            id = "tool_backup_chart_stun_leak",
            categoryId = "advanced_tools",
            title = "本地备份、WebDAV 云同步、流量图表、NAT 与 DNS 泄漏检测",
            badge = "工具箱合集",
            summary = "提供可读 JSON 完整配置灾备、WebDAV 云同步、实时流量波形图、NAT 探测与防缓存随机子域双路径 DNS 泄漏检测。",
            desc = "左侧边栏「工具」集成整套配置迁移与深度测试工具：\n" +
                "1. 本地备份与恢复：一键导出或恢复所有节点、订阅、可读 JSON 路由规则与全局偏好设定（完整涵盖分应用代理、TLS 分片、分流连接与 HWID 开关等；支持文件及剪贴板导出，请勿把含私密密码的备份上传公共网络）。\n" +
                "2. WebDAV 云端备份与同步：支持配置坚果云、Nextcloud、群晖 NAS 等标准 WebDAV 地址，方便多台手机平板跨端一键拉取最新配置（恢复时自动兼容历史备份目录）。\n" +
                "3. 实时流量图表：可视化波形实时查看节点吞吐峰谷。\n" +
                "4. NAT 类型探测 (STUN)：探测节点出口属于全锥形 Full Cone（最利好 Switch / PS5 联机与 P2P）还是对称形 Symmetric。\n" +
                "5. DNS 泄漏与 Fake-IP 状态检测：采用 32 位防缓存随机子域真实探测代理隧道 DNS 出口与本机物理系统链路出口，同步核对 Fake-IP 虚拟地址状态；如检测到中国大陆 DNS 泄漏会立即给出明确告警与排查指引。",
            notes = "首次调优好全部订阅、策略组和分流规则后，顺手做一次本地或 WebDAV 备份能彻底避免刷机换机重配痛苦；HWID 本机设备标识仅在本地生成，不会因跨机恢复备份导致多机同码冲突。",
            recommendation = "每次换新机器或做大规模规则折腾前先在「工具 - 备份」导出一份云端或本地存档。",
            keywords = "工具 备份 恢复 WebDAV 坚果云 Nextcloud 流量图表 NAT STUN Full Cone DNS泄漏 Fake-IP 检测 JSON 分应用 HWID",
        ),

        // 9. 疑难 FAQ
        DocEntry(
            id = "faq_wifi_failed_cellular_ok",
            categoryId = "faq",
            title = "连 Wi-Fi 时部分机场更新订阅、测速与连接失败，切流量正常？",
            badge = "高频排查",
            summary = "通常源自宽带与蜂窝网络跨网封锁差异、路由器明文 DNS 污染、公网阻断 UDP 或光猫/旁路由代理冲突。",
            desc = "如果同一个 Wi-Fi 下别的机场正常，只有特定一家或几家机场“更新订阅、测速、连接全部失败”，切换到手机 4G/5G 流量后全部恢复正常，主要原因及排查顺序如下：\n" +
                "1. 宽带运营商拦截与 DNS 污染（最常见）：\n" +
                "   - 未连代理时，更新订阅走的是系统网络与当前 Wi-Fi 路由器下发的明文 DNS；若该宽带或光猫拦截了该机场域名/入口 IP，或对订阅域名做了 DNS 污染劫持，Wi-Fi 下就会直接超时或报错。\n" +
                "   - 验证方法：Wi-Fi 下关闭 OwnBox，直接用手机浏览器打开该机场官网或订阅链接；如果同样打不开，说明确系该宽带/路由器网络侧封锁。\n" +
                "2. 企业/校园网限制 UDP 或非标准端口：\n" +
                "   - 若出问题的机场主力为 Hysteria2 / TUIC（基于 UDP/QUIC）或使用冷门端口，部分公司、酒店与校园 Wi-Fi 会直接丢弃 UDP 包或封端口，而手机流量正常放行。\n" +
                "3. 路由器旁路由冲突：若家里路由器本身开了透明代理且启用了 FakeIP，手机再开 OwnBox 会产生假 IP 冲突导致无网。\n" +
                "4. 临时应对建议：\n" +
                "   - 给路由器更换阿里/腾讯公共 DNS（如 223.5.5.5 / 119.29.29.29），关闭光猫自带“安全防诈骗”拦截；\n" +
                "   - 手机上用正常节点或流量连上 VPN 后，在订阅分组里开启「仅当连接时更新」走代理更新该机场订阅；\n" +
                "   - 核心设置保持「IPv6 模式 = 禁用」（防止部分仅有假 IPv6 的宽带导致握手卡死）。",
            notes = "如果别的客户端在同一个 Wi-Fi 下能正常更新与测通同一个机场而唯独 OwnBox 失败，请将 OwnBox「直连 DNS」确认恢复为 https://223.5.5.5/dns-query，在日志页面抓取报错（如 no such host、reset、timeout）核查定位。",
            recommendation = "先在浏览器裸连验证 Wi-Fi 宽带本身是否通该机场，再核查路由器 DNS/透明代理与「禁用 IPv6」设置。",
            keywords = "wifi 流量 机场 更新订阅 失败 测速失败 连接失败 dns 污染 宽带 拦截 旁路由 udp faq",
        ),
        DocEntry(
            id = "faq_connected_no_internet",
            categoryId = "faq",
            title = "已显示连接成功，但海外网页和软件都打不开？",
            badge = "高频排查",
            summary = "依次核对手机系统时间误差（必须与网络时间误差 60 秒内）、落地 IP 是否探测成功及 DNS / 分应用设置。",
            desc = "连上 VPN 但完全上不去外网，90% 以上来自以下四个基础环：\n" +
                "1. 手机系统时间未校准（极高频）：VMess、VLESS、Trojan、Shadowsocks 等现代加密协议严苛校验时间窗口，若手机手动调快或慢超过 60 秒，节点服务端会直接静默丢弃全部请求！请前往系统设置开启「自动确定日期和时间」。\n" +
                "2. 节点实际已过期或被墙：观察主页底部状态栏「落地 IP」显示，如果提示探测失败或仍然显示本地运营商地址，说明所选节点当前已不可用或机场流量耗尽。\n" +
                "3. DNS 或 IPv6 配置异常：检查「设置 - DNS 设置」中远程 DNS 是否被改错（默认 https://dns.google/dns-query）、是否开启了「FakeDNS」，并在「核心设置」确认「IPv6 模式」设为「禁用」。\n" +
                "4. 分应用代理遗漏：确认正在打开的浏览器或客户端软件没有被放进“排除黑名单”，或者若处于白名单模式确认已将其勾选纳入代理。",
            notes = "可同时切换单节点执行一次「URL 测试」，快速验证到底是个别节点失效还是全端本地环境异常。",
            recommendation = "先对准网络时间并做一次单节点 URL 测试，若节点有延迟却无网再用「设置 - 进阶设置 - 重置设置」一键还原核心参数。",
            keywords = "连上 上不去网 打不开 时间差 60秒 落地ip 订阅过期 分应用 校准时间 faq",
        ),
        DocEntry(
            id = "faq_push_delay_background",
            categoryId = "faq",
            title = "微信、QQ 收消息延迟或熄屏后后台断网？",
            badge = "高频排查",
            summary = "国产通讯软件建议走国内直连或从分应用代理绕过，同时关闭系统对 OwnBox 的电池省电限制。",
            desc = "遇到国产软件接收通知延迟或锁屏一会网络全断时，按以下 4 步根治：\n" +
                "1. 让国产软件直连：保持「智能分流」模式并确认分流规则正常工作，或者在「VPN 设置 - 分应用代理」中采用绕过模式直接将微信、QQ、钉钉排除出 VPN，直走物理网卡推送零延迟。\n" +
                "2. 放开系统电池省电策略：在手机系统应用管理中找到 OwnBox，将电池策略设为「无限制 / 允许完全后台高耗电」，并在最近任务界面将其加锁防止一键清理杀后台。\n" +
                "3. 开启唤醒重置连接：在「进阶设置」中打开「唤醒时重置连接」，亮屏瞬间自动刷新因基站休眠失效的旧 TCP 连接。\n" +
                "4. 极端省电机型开启唤醒锁：若手机息屏后无论如何都被系统深度睡眠断网，在「进阶设置」尝试打开「后台唤醒锁」。",
            notes = "国内应用一旦误走海外代理绕行一圈，哪怕节点再快，也会明显增加推送信令丢包与延迟。",
            recommendation = "将微信/QQ 设为分应用绕过走物理直连 + 授予 OwnBox 系统电池无限制权限，推送与续航兼得。",
            keywords = "微信 qq 推送 延迟 消息 收不到 息屏 断网 杀后台 电池优化 唤醒锁 wakelock faq",
        ),
        DocEntry(
            id = "faq_balancer_interval_tolerance",
            categoryId = "faq",
            title = "策略组检查间隔与切换容差怎么配置最合适？",
            badge = "调优指引",
            summary = "日常建议保持默认检查间隔 300 秒 + 切换容差 30ms，既省电防抖又可在故障与切网时立刻响应。",
            desc = "很多用户在调优「自动选择策略组」时会纠结检查间隔和切换容差到底填多少最科学：\n" +
                "1. 检查间隔（默认 300 秒 / 5 分钟）：\n" +
                "   - OwnBox 现已支持「冷启动种子预热 + 并发测速流式激活 + 业务建连 2 秒快速故障转移」，而且每次真实发生网络切换（Wi-Fi 与 5G 互切）都会自动触发 1 次重测，测速单次失败也会立即切换备用节点。\n" +
                "   - 因此定时检查间隔不必调得过短（例如 15 秒/30 秒），否则只会频繁全组测速耗费电量；日常保持默认 300 秒（或 180–600 秒）体验已经极好。\n" +
                "2. 切换容差（默认 30 毫秒）：\n" +
                "   - 30 ms 可以有效屏蔽公网平时的微小波动，避免看网页或聊天时无端切节点重连；\n" +
                "   - 若你只希望毫秒级紧追最低延迟且不在意稍频繁切点，可设为 10–20 ms 甚至 0 ms（需自行权衡长连接稳定性）。",
            notes = "只要保持「关闭切换断连」，即使触发一次节点优化切换，当前正在进行的下载和会话也不会断流。",
            recommendation = "日常通用场景直接沿用默认参数：检查间隔 300 秒 + 切换容差 30 ms + 关闭切换时断开连接。",
            keywords = "策略组 检查间隔 300秒 切换容差 30ms 50ms 怎么设 调优 推荐值 balancerInterval balancerTolerance",
        ),
        DocEntry(
            id = "faq_port_conflict",
            categoryId = "faq",
            title = "提示端口已被占用无法启动怎么处理？",
            badge = "冲突解决",
            summary = "当本机其他代理或网络工具占用 2080 或 9090 端口时，在入站和进阶设置中更换端口号即可。",
            desc = "若日志或界面提示端口绑定失败、address already in use，说明手机上同时运行的另一个网络工具、本地代理或调试终端已经占用了默认端口：\n" +
                "1. 混合代理入站端口冲突（默认 2080）：进入「设置 - 模式与入站设置」，将「混合代理端口」改为空闲端口（例如 2088 或 10808）；若完全不使用本机代理端口，也可直接启用「禁用混合入站」。\n" +
                "2. Clash API 端口冲突（默认 9090）：如开启了「启用 Clash API」但 9090 被占用，可进入「设置 - 进阶设置 - 全局自定义配置」按需指定端口，或彻底关闭未在使用的同类后台代理软件。",
            notes = "Android 同一时刻仅允许存在一个 VPN 服务在系统底层运行，开启 OwnBox 前建议先退出其他 VPN 类 App。",
            recommendation = "只保留单个主力代理软件运行；如需多工具共存，请把「混合代理端口」换为 2088 等自定义空闲端口。",
            keywords = "端口冲突 address already in use 绑定失败 2080 9090 无法启动 mixedPort faq",
        ),
        DocEntry(
            id = "faq_webdav_sync_failed",
            categoryId = "faq",
            title = "WebDAV 云端备份同步失败如何排查？",
            badge = "备份指引",
            summary = "坚果云请务必使用专门生成的“第三方应用授权密码”并核对完整 URL 结尾斜杠。",
            desc = "使用「工具 - WebDAV 备份同步」上传或拉取配置报错时，常见原因如下：\n" +
                "1. 未使用专用授权密码（针对坚果云最常见）：坚果云出于安全机制禁止第三方软件直接用网站登录主密码！必须登录坚果云官网「账户信息 -> 安全设置 -> 第三方应用管理」添加应用名称并复制系统生成的专用密码填入 OwnBox。\n" +
                "2. 服务器 URL 格式不完整：坚果云标准地址为 https://dav.jianguoyun.com/dav/（末尾通常建议带斜杠 /，具体路径可按服务端要求加子目录）；自建 Nextcloud 应指向其 WebDAV 完整路径（如 https://你的域名/remote.php/dav/files/用户名/）。\n" +
                "3. 代理与直连问题：如果自建 WebDAV 在海外或启用了特殊域，尝试在连上代理后再点击上传/恢复测试。",
            notes = "WebDAV 凭据中若密码含特殊字符，请避免多复制首尾空格。",
            recommendation = "国内多端同步首选免费稳定的坚果云 WebDAV，按官网教程生成独立应用授权密码后填入即可长期使用。",
            keywords = "WebDAV 备份 同步 失败 坚果云 Nextcloud 授权密码 401 403 url 斜杠 faq",
        ),
    )

    private val categoryById: Map<String, DocCategory> = categories.associateBy { it.id }

    fun filter(categoryId: String, query: String): List<DocListItem> {
        val tokens = query.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }

        val matchedByCategory = LinkedHashMap<String, MutableList<DocEntry>>()
        for (cat in categories) {
            matchedByCategory[cat.id] = ArrayList()
        }

        for (entry in entries) {
            if (categoryId != CATEGORY_ALL && entry.categoryId != categoryId) {
                continue
            }
            val category = categoryById[entry.categoryId] ?: continue
            if (tokens.isNotEmpty() && !matchesAllTokens(entry, category, tokens)) {
                continue
            }
            matchedByCategory[entry.categoryId]?.add(entry)
        }

        val result = ArrayList<DocListItem>()
        for (cat in categories) {
            val catEntries = matchedByCategory[cat.id].orEmpty()
            if (catEntries.isEmpty()) continue
            result.add(DocListItem.Header(cat))
            for (entry in catEntries) {
                result.add(DocListItem.Entry(entry))
            }
        }
        return result
    }

    private fun matchesAllTokens(
        entry: DocEntry,
        category: DocCategory,
        tokens: List<String>,
    ): Boolean {
        val searchableFields = listOf(
            entry.title,
            entry.badge,
            entry.summary,
            entry.desc,
            entry.notes,
            entry.recommendation,
            entry.keywords,
            category.chipLabel,
            category.headerTitle,
        )
        return tokens.all { token ->
            searchableFields.any { field -> field.contains(token, ignoreCase = true) }
        }
    }
}
