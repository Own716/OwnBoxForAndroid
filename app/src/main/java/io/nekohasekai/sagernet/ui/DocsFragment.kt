package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.ItemDocCardBinding
import io.nekohasekai.sagernet.databinding.ItemDocHeaderBinding
import io.nekohasekai.sagernet.databinding.LayoutDocsBinding
import io.nekohasekai.sagernet.widget.ListListener

class DocsFragment : ToolbarFragment(R.layout.layout_docs) {

    sealed class DocListItem {
        data class Header(
            val title: String,
            val desc: String,
        ) : DocListItem()

        data class Item(
            val category: String,
            val title: String,
            val badge: String,
            val desc: String,
            val prosCons: String,
            val recommendation: String,
            val keywords: String,
        ) : DocListItem()
    }

    private var _binding: LayoutDocsBinding? = null
    private val binding get() = _binding!!

    private val allItems = ArrayList<DocListItem>()
    private val displayItems = ArrayList<DocListItem>()
    private lateinit var adapter: DocsAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        _binding = LayoutDocsBinding.bind(view)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_documentation)

        initDocData()

        adapter = DocsAdapter()
        binding.docsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.docsRecycler.adapter = adapter

        displayItems.clear()
        displayItems.addAll(allItems)
        adapter.notifyDataSetChanged()

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty()
                binding.btnClearSearch.isVisible = query.isNotEmpty()
                filterDocs(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            binding.searchInput.text.clear()
            binding.searchInput.clearFocus()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun filterDocs(query: String) {
        displayItems.clear()
        if (query.isEmpty()) {
            displayItems.addAll(allItems)
        } else {
            var currentHeader: DocListItem.Header? = null
            var hasItemUnderHeader = false

            for (item in allItems) {
                when (item) {
                    is DocListItem.Header -> {
                        currentHeader = item
                        hasItemUnderHeader = false
                    }
                    is DocListItem.Item -> {
                        if (item.title.contains(query, ignoreCase = true) ||
                            item.desc.contains(query, ignoreCase = true) ||
                            item.prosCons.contains(query, ignoreCase = true) ||
                            item.recommendation.contains(query, ignoreCase = true) ||
                            item.category.contains(query, ignoreCase = true) ||
                            item.keywords.contains(query, ignoreCase = true)
                        ) {
                            if (!hasItemUnderHeader && currentHeader != null) {
                                displayItems.add(currentHeader)
                                hasItemUnderHeader = true
                            }
                            displayItems.add(item)
                        }
                    }
                }
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun initDocData() {
        allItems.clear()

        // 1. 用户界面设置
        allItems.add(DocListItem.Header("1. 用户界面设置 (UI Settings)", "控制主页呈现、通知栏网速、资产卡片、桌面图标及系统主题视觉风格"))
        allItems.add(
            DocListItem.Item(
                category = "用户界面设置",
                title = "显示直连的速度 (showDirectSpeed)",
                badge = "推荐: 开启 (展开分行架构)",
                desc = "在通知中心中展示未走代理的直连流量速度。2.9.10 遵循“折叠首屏优先与分行独立排布”原则：收起折叠状态下仅单行紧凑显示代理出站网速；下拉展开通知后，直连网速将独立成行展示（直连: ↑.. ↓..），绝不与代理网速同行并排挤占屏幕，兼顾首屏清爽与展开全景监控。",
                prosCons = "【利】在手机下拉通知中心中，不仅能实时查看代理出站网速，展开通知即可独立成行洞察微信、网银、国内视频等直连应用的真实数据吞吐，防范后台偷跑；且收起状态下零挤占；【弊】展开大卡片行数增加一行。",
                recommendation = "【最稳推荐：开启】全新分行排布架构，折叠清爽不拥挤，展开全景监控。",
                keywords = "直连速度 速度 通知中心 通知栏 showDirectSpeed 网速 偷跑 分行",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "用户界面设置",
                title = "速率更新间隔 (speedInterval)",
                badge = "推荐: 1000ms (默认)",
                desc = "设定主页状态栏以及系统常驻通知栏中，实时上传/下载速率数值的刷新频率周期（如 1000ms、1500ms、2000ms）。",
                prosCons = "【利】1000ms 刷新周期灵敏平滑；【弊】若设为过短（如 200ms）在低端机型上会微量增加 UI 刷新负载，设为过长则数值滞后。",
                recommendation = "【最稳推荐：保持默认 1000ms】在动态灵敏度与系统能效功耗间取得最佳平衡。",
                keywords = "网速 速率 刷新 间隔 通知栏",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "用户界面设置",
                title = "节点流量统计 (profileTrafficStatistics)",
                badge = "推荐: 开启",
                desc = "持久化记录并累积统计每个出站代理节点在历史连接中消耗的上传与下载流量总量。",
                prosCons = "【利】清楚洞察各节点流量消耗比例，方便排查跑流量异常；【弊】极老旧设备断电前有轻量本地 SQLite 写入。",
                recommendation = "【最稳推荐：开启】纯本地轻量记录，不产生网络开销，便于机场流量对账。",
                keywords = "流量 统计 上传 下载 消耗",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "用户界面设置",
                title = "通知栏显示分组 (showGroupInNotification)",
                badge = "推荐: 开启",
                desc = "控制通知中心中是否呈现订阅分组与策略组名称。全新 2.9.10 规范四大模板：【策略组开启】：主标题显示策略组名称（如“日本 · 最低延迟”），正文显示当前落地节点（当前: Japan 01）；【策略组关闭】：主标题直接显示当前连上的节点名，正文显示策略类型（策略: 最低延迟）；【单节点开启】：主标题显示“订阅组 · 节点名”；【单节点关闭】：主标题仅显示节点名。彻底移除冗余的“节点”二字，各状态标题与正文零重复。",
                prosCons = "【利】信息层级清晰明了，策略组与单节点均能完美自适应；开启时随时知晓分组归属与落地节点，关闭时极简聚焦；【弊】无负面影响。",
                recommendation = "【最稳推荐：开启】策略组与单节点自适应排布，信息一目了然。",
                keywords = "通知栏 分组 机场 订阅 策略组 最低延迟 负载均衡 节点",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "用户界面设置",
                title = "显示主页落地 IP (showLandingIp)",
                badge = "推荐: 开启",
                desc = "在主页底部状态栏实时探测并展示当前 VPN 出口真实的公网落地 IP、国家/地区国旗与运营商信息。",
                prosCons = "【利】彻底杜绝由于代理未走通或回源直连造成的“假翻墙”，防止真实网络位置泄露；【弊】每次切换节点会发起一次轻量 IP 探测接口请求。",
                recommendation = "【最稳推荐：强烈推荐开启】科学上网与防泄露最核心的安全视觉凭证，确保每一次网络通信都精准出境。",
                keywords = "落地 ip 归属地 国家 国旗 泄露",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "用户界面设置",
                title = "始终显示节点地址 (alwaysShowAddress)",
                badge = "推荐: 关闭",
                desc = "在主页节点卡片上明文显示该节点后端的真实服务器 IP 地址或域名，而非仅展示其别名备注。",
                prosCons = "【利】排查节点解析与服务器 IP 时一目了然；【弊】在公开场合、截图或录屏分享时容易意外泄露服务器资产域名。",
                recommendation = "【最稳推荐：保持默认关闭】保护隐私与机场安全；仅在技术调试时临时开启。",
                keywords = "地址 域名 隐藏 隐私 节点名",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "用户界面设置",
                title = "确认删除节点 (confirmProfileDelete)",
                badge = "推荐: 开启",
                desc = "在节点操作菜单中点击“删除”时弹出确认对话框，二次确认无误后再执行移除。",
                prosCons = "【利】防止单手误触或滑动误操作导致自建节点或辛苦调优的配置丢失；【弊】删除操作多一次点击。",
                recommendation = "【最稳推荐：开启】数据安全第一，杜绝手滑误删关键配置。",
                keywords = "删除 确认 弹窗 误删",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "用户界面设置",
                title = "置顶显示机场资产信息卡片 (show_subscription_info_card)",
                badge = "推荐: 开启",
                desc = "在订阅分组顶部以独立卡片高亮展示机场套餐的已用流量、剩余额度、总配额及服务到期倒计时。",
                prosCons = "【利】打开主页即可一览机场剩余资产，避免突发欠费断网；【弊】若仅使用自建单节点该卡片不适用。",
                recommendation = "【最稳推荐：开启】机场订阅用户最受好评的实用资产感知功能。",
                keywords = "订阅 资产 流量 剩余 到期 机场 卡片",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "用户界面设置",
                title = "桌面应用图标定制 (customIcon)",
                badge = "推荐: 按个人偏好",
                desc = "支持切换 APP 在手机启动器桌面上的应用图标外观（经典、极简纯白、纯黑深色、跟随 Android 12+ Material You 莫奈动态取色及自定义图标包）。",
                prosCons = "【利】满足个性化桌面搭配审美需求，莫奈图标与壁纸浑然一体，桌面更加美观且具备隐蔽性；【弊】修改后部分国产系统桌面需 1~2 秒重新加载缓存。",
                recommendation = "【最稳推荐：自由选用】对网络代理与核心性能零影响，按个人视觉喜好随心定制。",
                keywords = "图标 桌面 图标包 莫奈 换图标 自定义 icon pack",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "用户界面设置",
                title = "纯色主题与夜间模式 (appTheme / nightTheme)",
                badge = "推荐: 按个人偏好",
                desc = "支持经典黑、纯白及浅灰纯色主题与深色模式适配。",
                prosCons = "【利】纯净视觉体验，深色清爽护眼；【弊】无负面影响。",
                recommendation = "【最稳推荐：自由选用】对网络核心与底层代理协议零影响，按视觉喜好设定即可。",
                keywords = "主题 纯白 浅灰 经典黑 夜间 颜色",
            )
        )

        // 2. VPN 设置
        allItems.add(DocListItem.Header("2. VPN 设置 (VPN Settings)", "控制系统虚拟网卡 (TUN) 路由分流、开机自启、局域网共享与 MTU 性能"))
        allItems.add(
            DocListItem.Item(
                category = "VPN 设置",
                title = "自动连接 (isAutoConnect)",
                badge = "推荐: 开启 (日常使用)",
                desc = "当手机开机启动完成或 APP 在后台被系统重新拉起时，自动激活 VPN 服务并连接上次选中的稳定节点。",
                prosCons = "【利】全天候无感保护，重启手机无需手动点开 APP；【弊】若所选节点因欠费或被封失效，开机初期可能短暂影响部分联网。",
                recommendation = "【最稳推荐：拥有长期稳定节点的用户推荐开启】若节点经常变动则建议手动连接。",
                keywords = "自启 自动连接 开机 重启",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "VPN 设置",
                title = "应用分流 / 分应用代理 (proxyApps)",
                badge = "推荐: 开启 (白名单模式)",
                desc = "精确控制指定应用走代理通道，或使指定应用完全绕过代理直连互联网。",
                prosCons = "【利】配置为白名单（仅常用海外应用走代理）时，微信、支付宝、网银、国内游戏完全不经过 VPN，速度极速且绝不触发异地登录风控；【弊】初次使用需勾选需要代理的海外 App。",
                recommendation = "【最稳推荐：强烈推荐开启“分应用代理”，并勾选“绕过所选应用模式（黑名单）”或“仅代理海外常用应用（白名单）”】国内外应用互不干扰的最优解。",
                keywords = "分流 分应用 白名单 黑名单 微信 支付宝 银行",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "VPN 设置",
                title = "绕过局域网 (bypassLan)",
                badge = "推荐: 开启",
                desc = "在 Android 系统底层路由表中将私有内网网段（如 192.168.0.0/16、10.0.0.0/8 等）直接排除在 VPN 网卡之外。",
                prosCons = "【利】访问家用路由器后台、NAS 存储、局域网打印机、投屏设备畅通无阻，内网千兆传输不消耗手机 CPU；【弊】极罕见需要通过远程代理访问公司内网时需关闭。",
                recommendation = "【最稳推荐：强烈推荐开启】家用及办公网络环境绝对必备的稳定性基石。",
                keywords = "局域网 内网 bypassLan 路由器 nas 投屏",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "VPN 设置",
                title = "内核绕过局域网 (bypassLanInCore)",
                badge = "推荐: 关闭",
                desc = "把局域网私有网段的数据包先吞入 VPN 虚拟网卡，然后在内核路由规则层匹配直连（direct）出站。",
                prosCons = "【利】在少数不支持路由表排除的极度阉割定制安卓设备上有较好兼容；【弊】所有内网大流量拷贝均需经过内核用户态拷贝，消耗额外 CPU 与发热。",
                recommendation = "【最稳推荐：保持默认关闭】除非系统底层不支持系统级路由排除，否则优先使用系统级“绕过局域网”。",
                keywords = "内核 局域网 bypassLanInCore",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "VPN 设置",
                title = "严格路由模式 (strictRoute)",
                badge = "推荐: 开启",
                desc = "激活强隔离路由策略，清除物理网络接口上的默认路由表，强制所有出站流量必须经由 VPN 接口处理。",
                prosCons = "【利】最高级别防 DNS 旁路泄漏与 WebRTC 穿透，杜绝运营商旁路监控；【弊】在少数魔改多卡机型上偶发单卡网络切换延迟。",
                recommendation = "【最稳推荐：开启】彻底杜绝物理网络旁路偷跑流量与真实 IP 暴露。",
                keywords = "严格路由 strictRoute 泄露 隔离 webrtc",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "VPN 设置",
                title = "允许局域网设备连接 (allowAccess)",
                badge = "推荐: 平时关闭，按需开启",
                desc = "在手机上监听 0.0.0.0 局域网地址，允许处于同一 Wi-Fi 下的电脑、Switch、PS5、电视盒子连接手机的 IP:端口进行科学上网。",
                prosCons = "【利】一键将安卓手机化身为便携式局域网透明代理网关；【弊】在公共咖啡厅或机场 Wi-Fi 下开启可能被他人扫描探测甚至蹭网。",
                recommendation = "【最稳推荐：平时关闭，需要为主机/电脑共享网络时临时开启】安全与稳定兼备。",
                keywords = "共享 局域网 电脑 开热点 switch 代理网关",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "VPN 设置",
                title = "计费网络 (meteredNetwork)",
                badge = "推荐: 流量紧张开启，无限量关闭",
                desc = "向 Android 系统框架声明当前 VPN 接口为“按流量计费网络”。",
                prosCons = "【利】系统会自动暂停后台 Google Play 应用自动更新及云相册全量备份，防止机场流量被偷跑刷爆；【弊】部分依赖不限流量的后台同步会暂停。",
                recommendation = "【最稳推荐：机场每月套餐有限用户建议开启】防止百兆更新偷跑套餐。",
                keywords = "计费 流量 偷跑 更新 限制",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "VPN 设置",
                title = "TUN 实现模式 (tunImplementation)",
                badge = "官方原生: Sing-Tun (sing-box 1.15 标准协议栈)",
                desc = "OwnBox 已全面统一采用 sing-box 官方原生的 Sing-Tun 高性能用户态网络协议栈，移除了历史已废弃的 gVisor 协议栈。",
                prosCons = "【利】Sing-Tun 具备极高吞吐效率、优异的多路并发能力与超低内存及发热表现，与内核深度契合；【注意】内核已废弃第三方栈，全平台统一标准。",
                recommendation = "【当前标准：官方原生 Sing-Tun】系统已自动锁定最佳 Sing-Tun 协议栈，无需手动切换。",
                keywords = "tun sing-tun singtun 协议栈 网络栈",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "VPN 设置",
                title = "最大传输单元 MTU (mtu)",
                badge = "推荐: 9000 (默认) 或 1500",
                desc = "定义 VPN 虚拟网卡承载的单个数据包最大有效负载字节（默认 9000）。",
                prosCons = "【利】9000 巨型帧模式在内核与系统交互间拥有极高吞吐效率，内核自适应切片；【弊】在极个别严苛的运营商弱网环境下若出现分片黑洞可降至 1500 或 1400。",
                recommendation = "【最稳推荐：保持默认 9000】若在移动蜂窝下个别网页偶发加载卡死，可调整为 1500 稳妥标准值。",
                keywords = "mtu 分片 字节 巨型帧 卡顿",
            )
        )

        // 3. 模式与入站设置
        allItems.add(DocListItem.Header("3. 模式与入站设置 (Mode & Inbound Settings)", "配置应用运行形态、本地代理端口监听与认证安全"))
        allItems.add(
            DocListItem.Item(
                category = "模式与入站设置",
                title = "服务模式 (serviceMode)",
                badge = "推荐: VPN 模式 (默认)",
                desc = "选择运行模式为系统级 VPN 模式（全自动拦截系统网络）或纯本地仅代理模式（只在本地开放端口）。",
                prosCons = "【利】VPN 模式开箱即用，所有应用全自动受益；【弊】仅代理模式需要手动在 Wi-Fi 高级设置中填入 127.0.0.1 代理，仅适合特殊开发者。",
                recommendation = "【最稳推荐：VPN 模式】绝大多数用户的标准使用方式。",
                keywords = "vpn 仅代理 本地模式 端口",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "模式与入站设置",
                title = "禁用混合入站 (disableMixedInbound)",
                badge = "推荐: 关闭",
                desc = "关闭本地开放的 HTTP/SOCKS5 混合代理监听端口（默认 2080）。",
                prosCons = "【利】开启可杜绝本机暴露任何本地监听端口，适合极端安全环境；【弊】开启后本机的浏览器或终端无法通过 127.0.0.1:2080 使用代理。",
                recommendation = "【最稳推荐：保持默认关闭】保留本地混合端口，便于多工具协同调度。",
                keywords = "混合入站 2080 socks5 http 端口",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "模式与入站设置",
                title = "混合代理端口 (mixedPort)",
                badge = "推荐: 2080 (默认)",
                desc = "指定本机 HTTP 与 SOCKS5 共享监听的 TCP 端口号。",
                prosCons = "【利】默认 2080 兼容性广泛；【弊】若本机安装了其他冲突工具占用该端口会导致服务启动失败。",
                recommendation = "【最稳推荐：保持 2080】若与第三方应用冲突，可修改为 7890、10808 等空闲端口。",
                keywords = "端口 mixedPort 2080 7890 监听",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "模式与入站设置",
                title = "混合入站认证 (mixedAuthConfig)",
                badge = "推荐: 留空 (单机环境)",
                desc = "为本地 HTTP/SOCKS5 代理端口设置访问连接时必须提供的用户名与密码鉴权。",
                prosCons = "【利】防止未授权人员利用局域网代理；【弊】单机使用每次配置外部客户端需要输密码。",
                recommendation = "【最稳推荐：仅在开启“允许局域网连接”且在公共 Wi-Fi 时设置】家庭自用保持留空免密最舒适。",
                keywords = "密码 认证 用户名 鉴权 安全",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "模式与入站设置",
                title = "HTTP 代理绕过名单 (httpProxyBypass)",
                badge = "推荐: 保持默认",
                desc = "在使用系统全局 HTTP 代理时，配置直接绕过代理的域名与 IP 白名单（如 127.0.0.1, localhost, *.cn）。",
                prosCons = "【利】保障内网与直连站点不受 HTTP 代理影响；【弊】配置错误可能导致内网请求走外网失败。",
                recommendation = "【最稳推荐：保持默认】普通用户无需改动。",
                keywords = "绕过名单 http 白名单 域名",
            )
        )

        // 4. 核心设置
        allItems.add(DocListItem.Header("4. 核心设置 (Core Settings)", "配置内核流量嗅探、域名预解析、IPv6 路由与规则集引擎"))
        allItems.add(
            DocListItem.Item(
                category = "核心设置",
                title = "流量嗅探 (trafficSniffing)",
                badge = "推荐: 开启 (默认启用)",
                desc = "深入检查流经代理隧道的 TCP/UDP 首包（提取 TLS SNI、HTTP Host、QUIC 握手），反向提取出真实的域名地址。",
                prosCons = "【利】解决许多海外应用发起纯 IP 请求导致域名分流规则失效的痛点，大幅提升分流命中率；【弊】微量首包解析处理（<0.5ms）。",
                recommendation = "【最稳推荐：强烈推荐开启】现代规则智能分流与免配置测速不可或缺的基石。",
                keywords = "嗅探 trafficSniffing sni host quic 域名识别",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "核心设置",
                title = "解析目标地址 (resolveDestination)",
                badge = "推荐: 开启",
                desc = "在内核路由判定前，将嗅探或接收到的域名预先解析为 IP 地址，以匹配更完备的 GeoIP 规则。",
                prosCons = "【利】大幅增强基于 IP 归属地分流的准确度；【弊】配合不当可能触发额外 DNS 请求。",
                recommendation = "【最稳推荐：开启】OwnBox 内部已内置单栈防穿透过滤，开启可保障分流准确性最大化。",
                keywords = "解析目标地址 resolveDestination geoip 分流",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "核心设置",
                title = "IPv6 路由模式 (ipv6Mode)",
                badge = "推荐: 禁用 (DISABLE)",
                desc = "控制所有 IPv6 流量与 AAAA 域名解析的处理策略（禁用 / 自动 / 仅 IPv6 / 优先 IPv4）。",
                prosCons = "【利】选择“禁用”可彻底黑洞丢弃所有 IPv6 流量与 AAAA 查询，阻断运营商旁路泄露与 VPS 双栈穿透，彻底消除 Google/ChatGPT 人机验证与外网真实 IP 暴露；【弊】无法直接访问极少数纯 IPv6 专属网站。",
                recommendation = "【最稳推荐：强烈推荐选择“禁用 (DISABLE)”】这是目前国内外科学上网、消除风控、防止外网泄露【最稳定可靠】的黄金标准设置！",
                keywords = "ipv6 ipv4 禁用 泄露 纯ipv4 验证码 人机验证",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "核心设置",
                title = "双网络加速 / 多路径 (dualNetworkAcceleration)",
                badge = "推荐: 关闭",
                desc = "同时聚合利用 Wi-Fi 和蜂窝移动数据两个物理网络接口并发发送数据包提升带宽与抗抖动。",
                prosCons = "【利】网络切换时瞬间平滑无缝；【弊】移动蜂窝流量持续偷跑，且双出口可能触发部分机场节点的并发与多地 IP 登录风控封号。",
                recommendation = "【最稳推荐：推荐关闭】单网络出站最稳健，完全避免被机场误判多设备违规封禁。",
                keywords = "双网加速 多路径 移动数据 并发 流量 封号",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "核心设置",
                title = "并发连接建立 (concurrentDial)",
                badge = "推荐: 关闭",
                desc = "向目标节点建立 TCP 隧道时，同时发起多条拨号建链并取最先响应的一条。",
                prosCons = "【利】极限微幅压缩冷启动握手耗时；【弊】服务器端瞬间承受翻倍的连接握手压力，容易引起防火墙限速。",
                recommendation = "【最稳推荐：保持默认关闭】单路建链最温和稳定。",
                keywords = "并发拨号 concurrentDial 握手 延迟",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "核心设置",
                title = "规则集更新地址与间隔 (rulesProvider / rulesGeositeUrl / rulesGeoipUrl / rulesUpdateInterval)",
                badge = "推荐: 官方默认源，间隔 0 (手动)",
                desc = "配置精准分流所依赖的 Geosite（域名库）和 GeoIP（IP 分布库）数据库下载链接与自动定时更新频率。",
                prosCons = "【利】定期更新能收录最新国内直连白名单；【弊】若自动更新间隔太短在后台频繁下载几十兆大文件容易浪费流量。",
                recommendation = "【最稳推荐：保持官方源，间隔设为 0（手动按需更新）】每隔一两个月手动点一次更新最稳妥省流。",
                keywords = "规则 geosite geoip 数据库 更新 间隔 rulesUpdateInterval",
            )
        )

        // 5. DNS 设置
        allItems.add(DocListItem.Header("5. DNS 设置 (DNS Settings)", "杜绝 DNS 污染劫持、加速域名秒开与国内外智能分流"))
        allItems.add(
            DocListItem.Item(
                category = "DNS 设置",
                title = "远程 DNS 服务器 (remoteDns)",
                badge = "推荐: https://dns.google/dns-query",
                desc = "专门用于走代理通道出站解析海外被封锁/受污染域名的加密 DNS 服务器（DoH）。",
                prosCons = "【利】Google DoH 全球部署、Anycast CDN 极佳，走代理加密解析绝无污染可能；【弊】若填写不存在或失效的 DNS 会导致海外全局无法解析。",
                recommendation = "【最稳推荐：保持默认 https://dns.google/dns-query】全球解析一致性与稳定性最高。",
                keywords = "远程 dns doh google 8.8.8.8 加密 污染",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "DNS 设置",
                title = "直连 DNS 服务器 (directDns)",
                badge = "推荐: https://223.5.5.5/dns-query",
                desc = "专门用于在本地直接解析国内网站（如百度、淘宝、抖音、B站、知乎）的 DNS 服务器。",
                prosCons = "【利】阿里/腾讯国内 DoH 能以极高精度返回您本地宽带最近的国内 CDN 节点，秒开视频与图片；【弊】若填入海外 DNS 会导致国内网站被解析到偏远节点从而剧烈卡顿。",
                recommendation = "【最稳推荐：保持默认阿里 DNS (https://223.5.5.5/dns-query)】国内解析最快最准。",
                keywords = "直连 dns 阿里 223.5.5.5 腾讯 国内 延迟",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "DNS 设置",
                title = "启用 DNS 分流路由 (enableDnsRouting)",
                badge = "推荐: 开启",
                desc = "根据访问域名的类型自动分流：国内域名路由至国内直连 DNS，海外域名路由至海外加密远程 DNS。",
                prosCons = "【利】国内网站飞速秒开且 CDN 精准，海外网站彻底免疫 GFW DNS 污染；【弊】关闭后所有解析只能单一走一边。",
                recommendation = "【最稳推荐：强烈推荐开启】智能科学上网最核心的 DNS 分流大脑。",
                keywords = "dns 分流 路由 智能分流 污染 国内直连",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "DNS 设置",
                title = "启用 FakeDNS (enableFakeDns)",
                badge = "推荐: 开启",
                desc = "针对海外域名在本地立即分配并返回一个 198.18.x.x 的保留假 IP，由远端代理节点在出站端完成最终解析。",
                prosCons = "【利】客户端无需等待远端 DNS 往返耗时（实现 0-RTT 秒开），海外网页点开即开，彻底杜绝本地 DNS 泄漏；【弊】极少数需要直连纯公网 IP 校验的古董应用不兼容。",
                recommendation = "【最稳推荐：强烈推荐开启】大幅提升海外网页与社交软件首屏加载速度。",
                keywords = "fakedns 假ip 0-rtt 秒开 延迟 劫持",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "DNS 设置",
                title = "本地静态 Hosts (dnsHosts)",
                badge = "推荐: 留空",
                desc = "允许在本地手动强制绑定某些域名对应的固定 IP 解析映射。",
                prosCons = "【利】便于定向指定服务器解析 IP；【弊】当远程目标服务器迁移机房变更 IP 时会导致目标域名无法打开。",
                recommendation = "【最稳推荐：普通用户保持留空】除非有特定私有域名调试需求。",
                keywords = "hosts 静态 域名 映射 覆盖",
            )
        )

        // 6. 分片设置
        allItems.add(DocListItem.Header("6. 分片 (Fragment) 设置 (TLS Fragment Settings)", "针对特殊 SNI 审查阻断的混淆抗封锁工具"))
        allItems.add(
            DocListItem.Item(
                category = "分片设置",
                title = "启用 TLS 分片 (enableTLSFragment)",
                badge = "推荐: 默认关闭 (受阻断时开启)",
                desc = "将客户端与服务器 TLS 握手中的 Client Hello 封包切成若干微小片段交错发送，使得防火墙无法重组提取 SNI 域名。",
                prosCons = "【利】能有效拯救部分被运营商阻断 SNI 导致 TLS 频繁超时的节点；【弊】增加微量建链分包延迟，且个别苛刻的反代服务器可能拒绝异常 TCP 分片。",
                recommendation = "【最稳推荐：默认关闭】节点正常连通时无需开启；仅当节点出现“TCP 能通但 TLS 握手频繁超时”时再开启测试。",
                keywords = "分片 fragment tls sni 混淆 阻断 超时",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "分片设置",
                title = "分片长度与发送间隔 (fragmentLength / fragmentInterval)",
                badge = "推荐: 保持默认 100-200 / 10-20",
                desc = "定义每个 TLS 分片切割的字节长度范围（默认 100-200 字节）与分片发射间隔毫秒（默认 10-20ms）。",
                prosCons = "【利】默认参数是抗封锁测试得出的最优区间；【弊】分片太小会导致发包繁琐，间隔太大明显增加握手耗时。",
                recommendation = "【最稳推荐：保持默认 100-200 与 10-20】如非专业网络调试无需更改。",
                keywords = "分片长度 间隔 100-200 10-20 延迟",
            )
        )

        // 7. 连接观测与负载均衡
        allItems.add(DocListItem.Header("7. 连接观测与负载均衡 (Observatory & Balancer)", "节点测速、健康检查与多节点智能轮询优选配置"))
        allItems.add(
            DocListItem.Item(
                category = "连接观测与负载均衡",
                title = "连通性测试 URL (connectionTestURL)",
                badge = "推荐: 保持默认 Cloudflare 204",
                desc = "节点测速时用于发起探测的目标网址（默认 https://cp.cloudflare.com/generate_204）。",
                prosCons = "【利】返回纯空内容（204 No Content），测速极快且完全不耗费套餐流量，全球 Anycast CDN 节点覆盖；【弊】若误填为大文件网址会导致批量测速消耗大量流量。",
                recommendation = "【最稳推荐：保持默认 Cloudflare 204 或 Google 204】测速最轻量准确。",
                keywords = "测速 url 204 cloudflare google 探测 链接",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "连接观测与负载均衡",
                title = "自动隐藏不可用节点 (hideUnavailableProfiles)",
                badge = "推荐: 关闭",
                desc = "批量测速后，自动在列表中折叠或隐藏检测到超时与无法连通的节点。",
                prosCons = "【利】界面清爽只展示可用节点；【弊】容易让用户产生“节点配置丢失”的错觉，无法了解哪些节点掉线需要更新。",
                recommendation = "【最稳推荐：保持关闭】对全部节点状态保持可见，心中有数。",
                keywords = "隐藏 节点 不可用 过滤 丢失",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "连接观测与负载均衡",
                title = "测速模式 (speedTestMode)",
                badge = "推荐: HTTP RTT (默认)",
                desc = "测速算法标准：HTTP RTT（测量首包往返真实延迟）、TCP Ping（纯三次握手时间）、Download（下载真实速度）。",
                prosCons = "【利】HTTP RTT 能精准测出包含代理协议解密与远端建链的纯净 1-RTT 真实网页开屏时延，且零流量消耗；Download 测速每次消耗几十兆流量并给机场带来巨大并发压力。",
                recommendation = "【最稳推荐：HTTP RTT】以最轻量方式最真实反映节点可用性与网页秒开响应速度。",
                keywords = "测速模式 rtt ping 下载 算法",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "连接观测与负载均衡",
                title = "测速超时时间 (speedTestTimeoutMs)",
                badge = "推荐: 5000ms (5秒)",
                desc = "单个节点测速等待目标响应的最大时限。",
                prosCons = "【利】5000ms 既能容忍轻微网络抖动，又不会让整队列陷入漫长卡死；【弊】超时设置过短（如 1000ms）容易将高延迟但可用的节点误判为失联。",
                recommendation = "【最稳推荐：保持默认 5000ms】最科学的超时判定标准。",
                keywords = "超时 timeout 5000ms 测速等待",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "连接观测与负载均衡",
                title = "负载均衡策略与自动优选 (balancerStrategy)",
                badge = "推荐: 最低延迟优先",
                desc = "在负载均衡器中聚合多个节点，并配置“最低延迟优先 (round-robin + probe)”、“轮询 (round-robin)”或“随机 (random)”调度策略，支持自定义测试 URL 与观测间隔 (s)。",
                prosCons = "【利】“最低延迟优先”能在后台全自动监测节点健康度并无缝漂移到最快可用节点，实现 100% 高可用断线自愈；【弊】高频探活在大量节点场景下会轻量消耗测试流量。",
                recommendation = "【最稳推荐：推荐使用“最低延迟优先”，观测间隔保持 300s】兼顾断线毫秒级自愈与节约套餐流量。",
                keywords = "负载均衡 策略组 最低延迟 轮询 随机 balancer strategy 自动切换 间隔",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "连接观测与负载均衡",
                title = "负载均衡切换容差与单位 (balancerTolerance / balancerToleranceUnit)",
                badge = "推荐: 300ms 或 0.3s (默认)",
                desc = "配置基于最低延迟优选 (leastPing) 或 URLTest 策略时的节点切换容差阈值与时间单位（毫秒 ms / 秒 s）。底层根据选定单位自动精准换算为内核毫秒级容差参数。",
                prosCons = "【利】核心防抖动防断流机制！在 leastPing 算法中，当备用节点的实测延迟仅比当前节点低一点点时（未超过设定的容差差值），调度器绝不会盲目切换，从而彻底消除公共网络微小抖动导致的频繁切节点、网页重连与音视频会议瞬间断流；【弊】若将容差设为过大（如 > 2000ms），会导致当前节点性能严重劣变时切换迟钝。",
                recommendation = "【最稳推荐：保持默认 300ms 或 0.3s】完美过滤网络正常微幅波动，同时在节点发生真实故障或严重拥堵时仍能果断切换到高速节点。",
                keywords = "容差 tolerance 切换容差 容差单位 balancerTolerance 毫秒 秒 ms s leastping 抖动 断流 防抖动 负载均衡",
            )
        )

        // 8. 进阶设置
        allItems.add(DocListItem.Header("8. 进阶设置 (Advanced Settings)", "内核长连接自愈、安全策略、唤醒锁与日志调试"))
        allItems.add(
            DocListItem.Item(
                category = "进阶设置",
                title = "网络切换重置连接 (networkChangeResetConnections)",
                badge = "推荐: 开启",
                desc = "当手机从 Wi-Fi 切换到移动数据（或从一个 Wi-Fi 漫游到另一个 Wi-Fi）时，主动断开所有已失效的死连接并即时重新握手。",
                prosCons = "【利】彻底根除“离开 Wi-Fi 走在路上网络必定卡死转圈半天”的痛点，新网络秒级无感重连；【弊】无负面影响。",
                recommendation = "【最稳推荐：强烈推荐开启】移动端抗网络波动、防假死断流的最关键神级设置！",
                keywords = "网络切换 重置 假死 转圈 重连 wifi 蜂窝",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "进阶设置",
                title = "唤醒时重置连接 (wakeResetConnections)",
                badge = "推荐: 开启 (息屏易断流用户)",
                desc = "手机在熄屏休眠较长时间后重新解锁点亮屏幕时，主动刷新可能已被运营商基站静默超时的 TCP 长连接。",
                prosCons = "【利】解决亮屏瞬间微信等软件接收消息延迟转圈的问题；【弊】在频繁亮屏息屏时微量触发重新建链。",
                recommendation = "【最稳推荐：开启】保障亮屏即连，消除黑屏假死。",
                keywords = "唤醒 息屏 亮屏 休眠 假死 断流",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "进阶设置",
                title = "全局允许不安全证书 (globalAllowInsecure)",
                badge = "推荐: 强烈建议关闭",
                desc = "全局忽略所有 TLS 代理连接的证书有效性检查，允许自签名或过期证书通行。",
                prosCons = "【利】可连通自签名测试节点；【弊】丧失全部防中间人攻击（MITM）能力，在公共网络下流量可能被劫持嗅探，极大安全隐患！",
                recommendation = "【最稳推荐：绝对保持关闭】网络安全底线，切勿轻易全局开启！",
                keywords = "不安全 证书 tls allowInsecure 劫持 风险",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "进阶设置",
                title = "最低 TLS 协议版本 (appTLSVersion)",
                badge = "推荐: 1.2 (默认)",
                desc = "限制代理握手所允许协商的最低 TLS 加密版本（TLS 1.2 / TLS 1.3）。",
                prosCons = "【利】TLS 1.2 在老旧系统与各类 CDN 节点上拥有近乎 100% 的兼容性；【弊】强行锁定 1.3 会导致部分未支持 1.3 的老节点握手直接失败。",
                recommendation = "【最稳推荐：保持默认 1.2】兼容性与加密安全性完美兼备。",
                keywords = "tls 版本 1.2 1.3 握手 加密",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "进阶设置",
                title = "后台唤醒锁 (acquireWakeLock)",
                badge = "推荐: 保持关闭 (杀后台设备开启)",
                desc = "在 VPN 服务运行期间向系统申请 CPU 部分唤醒锁（PARTIAL_WAKE_LOCK），阻止 CPU 深度睡眠。",
                prosCons = "【利】彻底解决个别国产安卓魔改系统（如某些极端杀后台机型）熄屏后立刻杀死网络的问题；【弊】手机无法进入深度睡眠，略微增加待机耗电。",
                recommendation = "【最稳推荐：平时关闭】仅在熄屏后经常出现网络中断、收不到通知且已被系统杀后台时开启救急。",
                keywords = "唤醒锁 wakelock 耗电 杀后台 保活 熄屏",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "进阶设置",
                title = "日志级别 (logLevel)",
                badge = "推荐: warn 或 error",
                desc = "控制 sing-box 内核运行日志输出的详细程度（none / error / warn / info / debug / trace）。",
                prosCons = "【利】warn 或 error 模式下日志极简安静，零磁盘 I/O，最大化节省内存与电量；【弊】设为 debug/trace 会产生庞大日志流，在大流量下载时会剧烈卡顿拖慢速度。",
                recommendation = "【最稳推荐：日常使用设为 warn 或 error】日常使用切忌常驻开启 debug/trace！",
                keywords = "日志 log level debug trace warn error",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "进阶设置",
                title = "重置设置 (resetSettings)",
                badge = "推荐: 配置紊乱时使用",
                desc = "将应用全局所有功能配置一键还原为官方出厂的黄金推荐参数（不会删除您的节点和订阅）。",
                prosCons = "【利】当误改某些高级参数导致网络异常或断网时，一秒回滚到最稳定基准；【弊】自定义的个性化开关需要重新开启一次。",
                recommendation = "【最稳推荐：出现网络异常但排除节点原因时，随时使用“重置设置”一键自愈】",
                keywords = "重置 还原 恢复出厂 设置 异常 自愈",
            )
        )

        // 9. 侧边栏 Sing-box 仪表盘
        allItems.add(
            DocListItem.Header(
                "9. 侧边栏 Sing-box 仪表盘 (Dashboard)",
                "全景掌控内核网络运行脉络，毫秒级捕捉连接路由决策与吞吐速率"
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "侧边栏工具",
                title = "Sing-box 仪表盘 (menu_dashboard)",
                badge = "核心工具",
                desc = "1:1 完整移植的官方纯正 sing-box 实时网络仪表盘。需在「设置 - 进阶设置」中开启「启用 Clash API」后，即可随时从侧边栏快捷进入。支持在页面内一键切换 Zashboard (现代推荐 · 界面精美)、内置 Yacd (官方离线轻量) 或任意自定义 Web 面板链接；全面放行混合协议与跨域，自动注入本机 Clash API 参数直连。全面提供“概览、代理、规则、连接、配置、日志”多 Tab 导航视图，支持实时上传/下载流量动态图表、内核内存占用监控、策略组出站实时切换、活跃与历史连接多维过滤及一键断开等全套网络排查工具。",
                prosCons = "【利】全景掌控内核网络运行脉络，毫秒级捕捉每个应用与域名的连接路由决策、命中规则与吞吐速率，精准诊断跑流量、解析异常与断流节点；【弊】前台图表与连接高频轮询会占用微量 CPU 运算，退出仪表盘页面即刻自动挂起停止轮询，完全不损耗日常电量。",
                recommendation = "【推荐：调试必备】日常使用建议常驻开启 Clash API，遇到网络卡顿、分流疑难或需要监控抓包时随时从侧边栏进入仪表盘全景透视，亦可按需切换最顺手的现代化面板。",
                keywords = "仪表盘 仪表板 sing-box clash api zashboard yacd 概览 代理 规则 活跃连接 连接 日志 内存 监控 抓包 menu_dashboard",
            )
        )

        // 10. 附加工具与备份同步
        allItems.add(
            DocListItem.Header(
                "10. 附加工具与备份同步 (Additional Tools & Backup)",
                "数据灾备、多端云同步、网络质量深度排查与诊断辅助"
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "附加工具与备份同步",
                title = "本地配置备份与恢复 (localBackup)",
                badge = "推荐: 定期备份",
                desc = "支持将应用中所有节点、分组、分流路由规则及全局自定义偏好设置一键打包导出为带时间戳的 JSON 备份压缩文件，或通过系统剪贴板导出与快速导入。",
                prosCons = "【利】在更换手机、刷机重装或配置调优遇到不可逆问题时，一键满血还原所有数据；【弊】若将含有私有自建 VPS 密码的备份文件分享给他人可能泄露凭据。",
                recommendation = "【最稳推荐：重要配置调优完成后立即导出一次本地备份并妥善保存】私密备份切勿上传公开网络。",
                keywords = "备份 恢复 导出 导入 迁移 换机 json zip localBackup",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "附加工具与备份同步",
                title = "WebDAV 云端备份与同步 (webdavBackup)",
                badge = "推荐: 多设备用户推荐",
                desc = "通过行业标准 WebDAV 协议（坚果云、Nextcloud、群晖 Synology 等），将本机的全部节点配置与规则一键安全上传加密备份到私有云，并支持随时从云端拉取恢复。",
                prosCons = "【利】实现手机、平板、备用机之间配置多端云同步，无需通过微信/QQ中转文件；【弊】初次使用需在“附加工具 - WebDAV 设置”中填入服务器 URL、账户及应用授权密码。",
                recommendation = "【最稳推荐：国内用户推荐使用坚果云 WebDAV】配置简单，稳定可靠，多设备切换极其省心。",
                keywords = "webdav 云备份 同步 坚果云 nextcloud 群晖 云端 webdavBackup",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "附加工具与备份同步",
                title = "实时流量图表 (trafficChart)",
                badge = "推荐: 流量监测",
                desc = "以可视化动态波形图表实时渲染出站各节点的上行/下行速率与累计吞吐走势，支持按时间窗口缩放查看。",
                prosCons = "【利】直观捕捉网络突发大流量、测速峰值带宽及异常流量抖动；【弊】图表高频刷新微量增加前台渲染能耗，离开页面即销毁。",
                recommendation = "【最稳推荐：日常按需查看】用于检测节点极限真实带宽与稳定性压测。",
                keywords = "流量 图表 速率 监控 波形 峰值 trafficChart",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "附加工具与备份同步",
                title = "NAT 类型与 STUN 穿透探测 (stunTest)",
                badge = "推荐: 游戏/P2P 用户",
                desc = "利用 RFC 3489 / RFC 5389 STUN 协议，探测当前 VPN 网络出站环境下的 NAT 拓扑类型（Full Cone 全锥形、Restricted Cone 受限锥形、Port Restricted 端口受限锥形、Symmetric 对称形 NAT）。",
                prosCons = "【利】精准诊断当前节点是否支持 Full Cone NAT，判断是否适合用于 Nintendo Switch / PS5 联机联麦（NAT Type A/B）以及 BT/PT / BitTorrent P2P 穿透加速；【弊】普通网页浏览用户无需关心此指标。",
                recommendation = "【最稳推荐：联机游戏与 P2P 优先选用 Full Cone 节点】普通科学上网无需纠结 NAT 类型。",
                keywords = "nat stun 穿透 锥形 full cone 对称 联机 switch ps5 stunTest",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "附加工具与备份同步",
                title = "DNS 泄漏与 Fake-IP 状态检测 (dnsLeakTest)",
                badge = "推荐: 隐私安全体检",
                desc = "一键检测本地海外域名是否成功被内核 Fake-IP 虚拟地址池接管，同时通过公共安全探针检测公网出口 IP 与真实 DNS 链路是否存在旁路泄漏。",
                prosCons = "【利】即时验证“严格路由”与“FakeDNS”是否正常运转，确保真实地理位置与运营商 DNS 绝对不泄漏；【弊】检测时会发起一次对安全检测接口的请求。",
                recommendation = "【最稳推荐：开启 VPN 后建议执行一次检测】确认 Fake-IP 生效且无 DNS 泄漏后即可安心上网。",
                keywords = "dns 泄漏 fakeip 假ip 隐私 安全 探针 dnsLeakTest",
            )
        )

        // 11. 路由分流规则与高级编辑器
        allItems.add(
            DocListItem.Header(
                "11. 路由分流规则与高级编辑器 (Routing Rules & Advanced Editor)",
                "深度掌握内核分流匹配引擎、单条规则 OR/AND 条件语义、Go RE2 正则合法性校验与动作流向"
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "路由分流规则",
                title = "规则匹配架构与自上而下匹配原则 (ruleMatchOrder)",
                badge = "核心原理: First Match Wins",
                desc = "OwnBox 与 sing-box 核心的分流路由规则采用严格的“自上而下、先命中先执行 (First Match Wins)”机制。当一个网络连接由应用发起进入 VPN 隧道时，路由引擎会从规则列表的第一条规则开始逐条向下比对；只要有一条规则被完全命中，该连接就会立即被导向指定的出站目标（如代理、直连或阻止），后续的任何规则都不会再被比对。若遍历完所有自定义规则仍未命中，流量将自动落入底部的“默认出站 (Default Outbound)”。",
                prosCons = "【利】逻辑清晰、可预测性极高，可通过调整规则上下排序轻松实现局部优先级覆盖；【弊】若误将宽泛的全局放行或直连规则排在精细的特定域名代理规则之前，会导致后续精细规则永远失效。",
                recommendation = "【最佳实践】严格遵守“特殊规则在顶，通用规则在中，兜底放行/代理在底”的排列顺序。",
                keywords = "路由 规则 匹配 顺序 优先级 first match wins default outbound 默认出站 排序",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "路由分流规则",
                title = "单条规则内条件的逻辑关系：OR 与 AND 深度剖析 (ruleConditionLogic)",
                badge = "核心机制: 主体 OR / 约束 AND",
                desc = "理解单条规则内部多个条件的组合逻辑至关重要：\n1. 【同类条件与主体条件遵循 OR 关系】：在同一条规则内，填入的多个域名（如 google.com、youtube.com）、多个 IP CIDR、或多个应用包名之间，属于并列的“主体触发条件”，只要命中其中任意一个，该规则的主体部分即算满足；\n2. 【限制性维度与主体条件遵循 AND 关系】：协议类型 (Network: TCP/UDP) 和目标端口 (Port: 80, 443 等) 属于过滤约束条件，必须与主体条件“同时满足”。例如：若在规则中同时填写了域名 google.com 与端口 443，则只有访问 google.com 且目标端口为 443 的连接才会命中该规则；如果访问 80 端口则不会命中。",
                prosCons = "【利】既能单条规则聚合同类资产（减少规则总条数），又能精确限定特定协议与端口，避免误分流；【弊】初学者若误以为不同域名之间是 AND 关系，容易产生理解误区。",
                recommendation = "【设计准则】同一业务的不同域名/IP 建议归入同一条规则；若需对不同端口执行不同出站（如 Web 走代理、BT 走直连），则应拆分成两条独立规则并配置端口过滤。",
                keywords = "OR AND 逻辑 关系 域名 端口 协议 命中 组合 条件 限制 ruleConditionLogic",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "路由分流规则",
                title = "高级规则编辑器与 Go RE2 正则校验 (ruleEditorRegex)",
                badge = "推荐: 进阶配置利器",
                desc = "OwnBox 内置全新可视化高级路由规则编辑器，支持卡片表单与 JSON 代码模式双向同步。在配置域名正则匹配 (domain_regex) 时，编辑器内置了纯正的 Go 语言 RE2 正则引擎语法实时校验器。由于 sing-box 内核采用 Go 原生 RE2 引擎，不支持回溯与部分高级 PCRE 特性（如前瞻预查 (?=...)、后顾 (?<=...) 等），编辑器会在输入时毫秒级检测并高亮提示不兼容的正则语法，彻底杜绝因配置非法正则导致内核崩溃 (Panic) 或启动失败。",
                prosCons = "【利】可视化表单所见即所得，Go RE2 实时校验让语法错误在保存前即被拦截，确保内核 100% 稳定运行；【弊】RE2 语法不支持复杂的零宽断言等 PCRE 扩展。",
                recommendation = "【推荐】域名匹配优先使用 domain_suffix（后缀匹配）或 domain_keyword（关键词匹配），效率最高；仅在复杂多段匹配时使用 domain_regex 并留意编辑器的绿色校验提示。",
                keywords = "高级编辑器 规则 正则 re2 go 正则表达式 校验 语法 崩溃 panic domain_regex",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "路由分流规则",
                title = "动作 (Action) 与阻断模式 (reject: drop vs reply) (ruleActions)",
                badge = "重要: 行为控制",
                desc = "规则命中后的执行动作支持：\n1. route：导向指定节点、策略组或国内直连；\n2. reject：阻断连接，并支持两种阻断模式：\n   - drop（静默丢弃）：内核直接丢弃该数据包，不向发送端发送任何响应。特点是隐蔽、防端口探测，但客户端可能会反复重试直到超时；\n   - reply（主动拒绝）：内核立即向发送端返回 TCP RST 报文或 ICMP Port Unreachable 报文。特点是客户端秒级感知连接失败，不再耗费电量重试，适合用于广告拦截 (AdGuard / Reject)；\n3. hijack-dns：将目标为 53 端口的 DNS 报文无缝劫持入 sing-box 内置 DNS 解析模块；\n4. resolve：强制触发内核对域名的底层预解析。",
                prosCons = "【利】针对广告拦截选用 reply 可显著加速网页加载并省电；针对黑客探测选用 drop 可防止暴露存在；【弊】若误对正常通信设置 reject 会导致应用无法联网。",
                recommendation = "【推荐】拦截流氓广告或追踪器规则推荐选用 reject (reply) 模式，客户端秒级放弃请求，界面不再转圈圈。",
                keywords = "action 动作 reject drop reply 阻断 广告 拦截 hijack-dns 劫持 重定向",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "路由分流规则",
                title = "二进制规则集 (.srs) 与规则集引用 (ruleSetEngine)",
                badge = "推荐: 高效分流",
                desc = "sing-box 官方采用的高性能编译型二进制规则集格式 (.srs)，相比传统的海量纯文本域名/IP 列表，解析速度提升数十倍，内存占用锐减 80% 以上。OwnBox 全面支持在线添加、自动更新与离线缓存官方及第三方 GeoIP、GeoSite 规则集，并在高级规则中直接通过 rule_set 字段引用。",
                prosCons = "【利】成千上万条规则编译为单一二进制块，内核启动仅需几毫秒，内存占用极低；【弊】二进制文件无法直接用纯文本编辑器直接查看源码（可通过 sing-box rule-set decompile 解码）。",
                recommendation = "【推荐开启并定期更新规则集】在主页侧边栏或规则设置中按需更新 geosite 与 geoip，保持国内外分流最新。",
                keywords = "srs 规则集 二进制 geosite geoip 性能 内存 rule_set 高效",
            )
        )

        // 12. 分应用代理与 Google Play 生态机制
        allItems.add(
            DocListItem.Header(
                "12. 分应用代理与 Google Play 生态 (Per-App Proxy & Google Play)",
                "揭秘 Android 系统 VPN 边界、为什么只选 Google Play 下不动软件、以及 OwnBox 深度联动解决方案"
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "分应用代理",
                title = "Android 系统分应用代理 (Per-App Proxy) 底层原理 (perAppVpnPrinciple)",
                badge = "底层机制",
                desc = "Android 操作系统的 VpnService 提供了基于 UID 与包名的流量隔离机制（addAllowedApplication 白名单模式 / addDisallowedApplication 黑名单模式）。该机制在 Linux 内核套接字 (Socket) 层面生效：只有被列入白名单的应用，其发起的 TCP/UDP 套接字才会被系统允许路由进 VPN 虚拟网卡 (TUN)；不在名单中的应用流量，会被操作系统底层强制直接发往物理网络接口（Wi-Fi 或移动数据蜂窝网卡），彻底绕过 VPN 核心。",
                prosCons = "【利】在操作系统最底层隔离流量，白名单外的应用完全不经过 VPN 核心，不产生任何解析与转发开销；【弊】若遗漏了某个应用依赖的底层系统共享服务（如系统下载管理器），会导致该应用的部分后台网络功能异常。",
                recommendation = "【使用提示】若开启白名单模式，请确保所有需要联网的关联服务均已包含在白名单内。",
                keywords = "分应用代理 白名单 黑名单 vpnservice uid tun 套接字 隔离 底层原理 perApp",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "分应用代理",
                title = "为什么只勾选 Google Play 依然下载不了软件？(googlePlayDownloadMystery)",
                badge = "深度揭秘: 必读痛点",
                desc = "这是几乎所有 Android 代理客户端用户的核心痛点：在分应用代理中明明勾选了“Google Play 商店” (com.android.vending)，却发现能够正常搜索应用、浏览评论和详情，但一旦点击“安装”或“更新”，进度条就一直卡在“正在下载”或 0%，甚至偷偷走国内直连流量！\n【真相深度揭密】：在 Android 生态架构中，Google Play 客户端自身实际上只负责商城界面的展示和任务触发；当用户点击下载后，Google Play 会通过 IPC 调用 Android 系统的“下载管理器” (com.android.providers.downloads / com.android.providers.downloads.ui) 以及 Google Play 服务核心 (com.google.android.gms / com.google.android.gsf) 代为建立网络连接并拉取 APK 安装包！\n如果您的分应用代理白名单里只勾选了 com.android.vending，那么下载管理器发起的真正大文件下载请求在第一道关卡就会被 Android 系统阻挡在 VPN 外面，直接走物理网卡直连国内，导致连接被阻断或下载失败！",
                prosCons = "【利】彻底弄懂 Android 系统组件代下载机制，不再困惑于“为什么商店能开但下载不动”；【弊】需要了解系统组件的包名协作关系。",
                recommendation = "【重要】使用分应用代理时，必须确保将系统下载管理器与 GMS 组件一并纳入代理名单。",
                keywords = "google play 商店 下载 卡 0% 等待中 downloadmanager 下载管理器 gms gsf vending providers",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "分应用代理",
                title = "OwnBox 智能联动机制与最佳分流实践 (googlePlayOwnBoxSolution)",
                badge = "推荐: 最佳配置方案",
                desc = "OwnBox 针对此痛点进行了深度架构优化：\n1. 【智能白名单联动】：在分应用代理中勾选 Google Play (com.android.vending) 时，OwnBox 会自动弹出提示或联动将下载管理器 (com.android.providers.downloads) 及 GMS 服务纳入 VPN 保护隧道，保证下载流量合法进入 TUN 网卡；\n2. 【TUN 内部精准分流】：流量进入 TUN 后，再交由 sing-box 路由规则引擎处理。OwnBox 内置的 Google 专线规则会将 Google Play 的商店 API 域名与 APK CDN 域名（如 *.gvt1.com、*.ggpht.com、*.1e100.net 等）统一精准分流到您指定的境外代理出站，而下载管理器代下的国内普通应用流量依然能够命中直连规则走国内直连，做到“既能秒下 Google Play，国内下载又不浪费代理流量”。",
                prosCons = "【利】一键解决 Google Play 下载断流卡顿，兼顾国内应用极速直连；【弊】无。",
                recommendation = "【推荐方案】开启分应用代理并勾选 Google Play + 智能联动，配合路由规则中的 Google 专线，体验最顺滑的 Google 生态应用更新。",
                keywords = "google play 联动 解决方案 cdn gvt1 专线 分流 下载管理器 最佳实践",
            )
        )

        // 13. Sing-box 自定义仪表盘深度实操指南
        allItems.add(
            DocListItem.Header(
                "13. Sing-box 仪表盘实操指南 (Sing-box Dashboard Guide)",
                "Clash API 9090 核心机制、多面板特性对比（Zashboard/YACD/MetaCubeXD）、自定义 URL 导入与跨域混合内容放行"
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "Sing-box 仪表盘",
                title = "Clash API 9090 控制器与 Secret 鉴权机制 (clashApiPrinciple)",
                badge = "核心基础: 需开启",
                desc = "sing-box 内核原生内置了与开源 Clash 协议高度兼容的 RESTful 控制接口（默认监听 127.0.0.1:9090）。该接口是所有 Web 仪表盘能够实时读取节点列表、执行 URLTest 延迟测速、拉取上行/下行速率波形图、抓取活跃/历史连接细节以及查看内核内存占用的核心桥梁。需在「设置 - 进阶设置」中勾选「启用 Clash API」后生效，并可自定义端口与鉴权 Secret（默认免密或配置密码增强本地防探测）。",
                prosCons = "【利】为本地与外部监控提供标准化数据接口，支持可视化运维；【弊】若未开启 Clash API，侧边栏仪表盘将无法连接到内核。",
                recommendation = "【推荐开启】日常使用建议常驻开启「启用 Clash API」，享受一站式掌控内核运行态势的便利。",
                keywords = "clash api 9090 仪表盘 secret 鉴权 控制器 rest 延迟 测速 内存 监控",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "Sing-box 仪表盘",
                title = "现代化面板：Zashboard 特性与开箱即用 (zashboardGuide)",
                badge = "强烈推荐: 颜值首选",
                desc = "Zashboard 是目前开源社区公认界面最优雅、交互最现代化的代理内核仪表盘。OwnBox 已对 Zashboard 进行原生集成与深度调优：\n1. 【自动免密注入】：从侧边栏进入 Zashboard 时，OwnBox 会自动将本地 Clash API 端口 (9090) 及访问 Secret 注入到页面请求参数中，彻底免去手动填入 IP/端口/密码的繁琐；\n2. 【纯正 WebSocket 极速双向流】：实时推流上传/下载双向网速与内存柱状图，毫秒级捕捉节点切换与连接通断；\n3. 【全多维多主题适配】：完美支持沉浸式暗黑深色模式与跟随系统主题，节点列表支持按地区、延迟、名字多维度筛选排序。",
                prosCons = "【利】界面极度精致，交互流畅顺滑，信息呈现直观完整，全自动登录开箱即用；【弊】视觉动效与图表渲染在极低端手机上占用稍多图形内存。",
                recommendation = "【首选推荐】日常监控、测速与节点切换强烈推荐使用 Zashboard。",
                keywords = "zashboard 仪表盘 现代 自动登录 免密 颜值 图表 websocket 暗黑 主题 推荐",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "Sing-box 仪表盘",
                title = "轻量经典：内置 Yacd 与 MetaCubeXD 面板对比 (yacdAndMetaGuide)",
                badge = "多样选择",
                desc = "除了 Zashboard，OwnBox 还提供了多种面板选择：\n1. 【内置 Yacd 面板】：完全离线打包在 APK 本地资产中，零外部网络请求，加载速度极快，内存占用极低，经典复古的四栏卡片布局，适合网络极差或只想极速查个延迟的场景；\n2. 【MetaCubeXD 进阶面板】：面向资深极客用户，具备最强大的路由规则诊断树状图、支持深度连接属性（DNS 响应、真实对端 IP、链式代理跳数）多重筛选。OwnBox 已对 MetaCubeXD 实现零配置鉴权适配，打开即自动注入本地 Clash API 与密钥并自动连接，无需手动输入复杂的 Secret；\n3. 【一键复制密钥】：仪表盘管理弹窗顶部直观展示当前本地 API 地址与密钥，支持一键复制到剪贴板，便于各类面板快速授权。",
                prosCons = "【利】按需选择，离线有 Yacd，极客有 MetaCubeXD，日常有 Zashboard；全部预设均享零配置免密直连；【弊】不同面板的操作习惯略有差异。",
                recommendation = "【建议】日常使用 Zashboard；排查复杂路由规则判定使用 MetaCubeXD；网络极弱离线使用 Yacd。",
                keywords = "yacd metacubexd 离线 极客 面板 规则诊断 连接排查 比较 选型 免密 复制密钥",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "Sing-box 仪表盘",
                title = "自定义 Dashboard URL 导入规范与混合内容安全放行 (customDashboardUrl)",
                badge = "高级功能",
                desc = "OwnBox 支持用户在「仪表盘管理」中添加任意外部部署的 Dashboard Web 地址（如内网 NAS 部署的 http://192.168.1.100:8080 或公网部署的 https 面板）。全新优化的「保存并应用」机制支持在输入自定义 URL 后立即生效并加载面板，无需额外手动切换。\n针对 Android WebView 普遍存在的“在 HTTPS 网页中请求本地 HTTP 127.0.0.1:9090 会被系统拦截（混合内容 Mixed Content 阻断）”问题，OwnBox 在底层 WebView 客户端中对仪表盘页面放行了安全的混合内容策略并开启跨域支持，确保无论是自建面板还是云端面板均能无缝直连本地 Clash API。",
                prosCons = "【利】自由度极高，支持私有化部署看板与多端统一管理，保存并应用一步到位；【弊】导入未经审查的第三方外部不可信网址可能存在前端脚本钓鱼风险。",
                recommendation = "【安全提示】仅添加可信的官方开源仪表盘项目（如 GitHub 开源仓库托管的 Pages 页面或本地自建容器）。",
                keywords = "自定义 url 仪表盘 保存并应用 混合内容 mixed content https http 跨域 webview 放行",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "Sing-box 仪表盘",
                title = "仪表盘连接失败与 Connection Refused 排查指南 (dashboardTroubleshoot)",
                badge = "排查指引",
                desc = "如果在打开仪表盘时提示“Connection Refused”、“无法连接到内核 API”或一直转圈：\n1. 检查 VPN 状态：请确保 OwnBox VPN 服务已经处于“已连接”状态；\n2. 检查 Clash API 开关：进入「设置 - 进阶设置」，确认「启用 Clash API」已勾选并处于开启状态；\n3. 检查端口与密码：确认「Clash API 端口」保持在 9090（若被其他应用占用可更改并在面板中同步修改）；若设置了 Secret 密码，请确认面板输入的密码与设置完全一致；\n4. 重启核心：若之前进行了热更新，点击主页浮动开关断开并重新连接一次 VPN，确保控制接口成功绑定启动。",
                prosCons = "【利】按部就班迅速定位连接故障；【弊】无。",
                recommendation = "【建议】遇到连接异常时首先确认 VPN 是否连接且 Clash API 是否开启。",
                keywords = "仪表盘 无法连接 connection refused 失败 排查 9090 clash api 密码 重启",
            )
        )

        // 14. 策略组调度算法与选型指南
        allItems.add(
            DocListItem.Header(
                "14. 策略组调度算法与选型 (Strategy Group & Balancer)",
                "最低延迟、最低负载、轮询、随机、一致性哈希与故障转移算法全解析"
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "策略组调度算法",
                title = "最低延迟 (least-ping / urltest) 选型场景 (stratLeastPing)",
                badge = "推荐: 网页浏览首选",
                desc = "最低延迟算法在后台定期通过 URLTest 向指定的高可靠探针地址（如 http://cp.cloudflare.com/generate_204）发送探测请求，动态记录组内每个节点的 TCP/握手 RTT 往返时延，并自动将流量优先调度到延迟最低、响应最快的活跃节点上。",
                prosCons = "【利】时刻享受最轻快秒开的网页交互体验，节点变慢或抖动时自动迁移到更优节点；【弊】后台定期探测产生极微量的流量消耗。",
                recommendation = "【最稳推荐】日常网页浏览、社交媒体沟通、技术查资料强烈推荐使用“最低延迟”策略组。",
                keywords = "最低延迟 least-ping urltest 策略组 网页 测速 推荐 自动切换",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "策略组调度算法",
                title = "最低负载 (least-load) 选型场景 (stratLeastLoad)",
                badge = "推荐: 大并发/下载用户",
                desc = "最低负载算法会统计当前核心与各个出站节点之间正在维持的活跃连接数与信道承载负荷，将新发起的连接优先派发给当前承载连接最少、最为闲置的节点。",
                prosCons = "【利】避免大文件下载或多线程并发时单个节点带宽被打满而发生拥塞丢包，实现多节点并发分流加速；【弊】无法保证每个选中的节点在地理上都是延迟最低的。",
                recommendation = "【适用场景】适合有大量并发请求、观看高清 4K 视频、批量下载大文件的重度用户。",
                keywords = "最低负载 least-load 负载均衡 并发 连接数 下载 大流量",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "策略组调度算法",
                title = "轮询 (round-robin) 与随机 (random) 选型场景 (stratRoundRobin)",
                badge = "通用调度",
                desc = "轮询 (round-robin) 算法将到来的新连接按组内节点列表顺序依次循环派发（节点A -> 节点B -> 节点C -> 节点A）；随机 (random) 算法则完全基于随机数进行无偏差分流。",
                prosCons = "【利】算法极其轻量，零 CPU 调度开销，天然将流量绝对均匀地摊薄到机场所有节点上；【弊】可能导致同一网站的多个连续请求落到不同国家的节点上，触发网站异地登录风控。",
                recommendation = "【使用提示】适合用于 API 爬虫抓取、大批量无状态并发请求等不需要保持持久会话的场景。",
                keywords = "轮询 round-robin 随机 random 均匀分摊 调度 算法",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "策略组调度算法",
                title = "一致性哈希 (consistent-hash) 选型场景 (stratConsistentHash)",
                badge = "强烈推荐: 账号登录/电商海淘",
                desc = "一致性哈希算法根据目标请求的主机名 (Host / Domain) 或目标 IP 进行哈希环计算映射。其最核心的特性是：对于同一个目标域名（例如 *.github.com、*.chatgpt.com、*.paypal.com），无论何时访问，都会始终被映射并经由同一个代理节点出境！",
                prosCons = "【利】完美解决多节点负载均衡导致的“上一秒在香港、下一秒变日本”引发的频繁要求重新输入验证码、异地封号风险、Session 掉线等致命问题，兼顾了多节点分流与单站点连接稳定性；【弊】单站点无法利用多节点并发带宽。",
                recommendation = "【强烈推荐】经常使用 ChatGPT、PayPal、Google 账号、各大网银及海淘购物的用户，策略组务必选配“一致性哈希”！",
                keywords = "一致性哈希 consistent-hash chatgpt 封号 登录 掉线 session 验证码 推荐 账号安全",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "策略组调度算法",
                title = "故障转移 (failover) 选型场景 (stratFailover)",
                badge = "推荐: 自建稳定主力",
                desc = "故障转移算法永远固定使用列表中排序在第一位的“主节点”；仅当检测到主节点连续多次探测失败（心跳超时断连）时，才会瞬间降级切换至第二位的“备用节点”；当主节点恢复健康后，又会自动回切回主节点。",
                prosCons = "【利】出口 IP 极其固定稳定，只有真正发生故障才切换，绝不无故漂移；【弊】备用节点平时处于闲置备胎状态。",
                recommendation = "【适用场景】自建高速优质 VPS 主力节点、配以机场节点作为备用灾备容灾的进阶用户首选。",
                keywords = "故障转移 failover 主备 容灾 降级 固定ip 稳定",
            )
        )

        // 15. 常见疑难排查与实战 FAQ
        allItems.add(
            DocListItem.Header(
                "15. 常见疑难排查与实战 FAQ (Troubleshooting & FAQ)",
                "汇总科学上网常见疑难：连上上不去网、Fake-IP 原理、后台断连推送延迟、端口冲突与 IPv6 泄漏"
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "常见疑难排查 FAQ",
                title = "连接成功有小钥匙，但国外网站全部打不开？ (faqConnectedNoInternet)",
                badge = "高频排查 1",
                desc = "排查思路：\n1. 【检查系统时间戳是否准确】：V2Ray / VMess / VLESS / Trojan / Shadowsocks 等现代协议要求手机本地时间与节点服务器时间差必须在 60 秒以内，若手机时间慢了或快了会导致握手鉴权被服务器直接拒绝！请在手机系统设置中开启“自动从网络同步时间”；\n2. 【检查落地 IP 是否显示】：观察主页底部“落地 IP”卡片，若显示“探测失败”或真实国内运营商 IP，说明节点已被阻断或机场订阅已过期；\n3. 【排查 DNS 配置】：在「DNS 设置」中尝试将远程 DNS 切换为 8.8.8.8 或 1.1.1.1，并开启 Fake-IP 模式以避开本地 DNS 投毒；\n4. 【检查分应用代理】：确认浏览网页使用的浏览器已纳入代理名单中。",
                prosCons = "【利】快速自查，99% 的连上无法上网均由此类基础环境问题引起；【弊】无。",
                recommendation = "【优先检查】首先对准北京时间校准手机系统时钟，然后查看落地 IP 是否正常。",
                keywords = "连上 上不去 打不开 时间差 ntp 证书 握手 失败 故障 钥匙 faq",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "常见疑难排查 FAQ",
                title = "什么是 Fake-IP？我该开启它吗？ (faqFakeIpPrinciple)",
                badge = "核心科普",
                desc = "【Fake-IP 原理】：传统 DNS 是手机先向远程 DNS 询问域名获得真实 IP 后再去发起连接；而 Fake-IP 模式下，当应用发起域名解析时，VPN 核心立即在本地为其伪造分配一个保留私网网段的虚拟 IP（如 198.18.0.1），实现 0 毫秒极速 DNS 响应。随后应用向该虚拟 IP 发起 TCP 请求时，核心再从本地映射表中查出真实域名，并直接将域名委托给远端境外出站节点去解析与连接！\n【巨大优势】：1. DNS 解析延迟变为 0ms，秒开国外网页；2. 本地完全不产生真实 DNS 请求，从物理上 100% 根绝 DNS 污染与 DNS 泄露；3. 远端节点代为解析域名能获得海外 CDN 最优最近的服务器 IP。",
                prosCons = "【利】网页秒开、防 DNS 污染、体验极致丝滑；【弊】极少数不支持虚拟 IP 映射的老旧局域网打印机或私有联机协议可能需加直连例外。",
                recommendation = "【最稳推荐：开启 Fake-IP】现代代理客户端的标配核心技术，全面提升上网体验。",
                keywords = "fake-ip fakeip 假ip 0ms 秒开 dns 污染 泄露 原理 优势 推荐 虚拟ip",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "常见疑难排查 FAQ",
                title = "微信/QQ 接收消息延迟或后台收不到通知？ (faqPushDelay)",
                badge = "高频排查 2",
                desc = "排查与解决建议：\n1. 【规则分流确保直连】：在路由规则中确保包含国内直连规则，让微信、QQ 等国内通讯软件走 Direct 国内直连，避免被绕到境外造成推送延迟；\n2. 【开启保持 CPU 唤醒锁 (WakeLock)】：在「设置 - 进阶设置」中开启「保持 CPU 唤醒」，防止 Android 深度息屏休眠时关闭网络通道；\n3. 【授予系统“电池优化免杀”白名单】：在系统设置中找到 OwnBox 与微信，将电池策略调整为“无限制 / 允许后台高耗电运行”，防止系统杀死后台守护进程；\n4. 【检查分应用代理】：若开启了分应用白名单，建议直接将微信等排除在代理之外走物理直连。",
                prosCons = "【利】彻底保障即时通讯消息毫秒级到达，不错过重要工作与社交信息；【弊】微量增加息屏功耗。",
                recommendation = "【建议】国内通讯应用一律保持直连，并开启 OwnBox 电池不优化权限。",
                keywords = "微信 qq 推送 延迟 收不到 消息 后台 杀后台 电池 唤醒锁 wakelock 直连",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "常见疑难排查 FAQ",
                title = "提示端口冲突 (Bind: address already in use)？ (faqPortConflict)",
                badge = "高频排查 3",
                desc = "原因分析：OwnBox 默认会在本地监听 Mixed 混合入站端口（默认 2080）以及 Clash API 控制端口（默认 9090）。如果手机上同时安装了其他网络工具（如 Termux、AdGuard、Clash、V2rayNG 等）且其后台服务未彻底退出，就会霸占 2080 或 9090 端口，导致 OwnBox 启动失败并抛出 address already in use 错误。\n解决办法：进入「设置 - 模式与入站设置」，将混合代理端口修改为其他空闲端口（例如 2088 或 10808）；在「设置 - 进阶设置」中将 Clash API 端口修改为 9099，然后重连即可。",
                prosCons = "【利】避开端口碰撞，和平共存；【弊】修改后若有局域网设备连接需同步修改端口。",
                recommendation = "【建议】避免多个 VPN/代理客户端同时在后台驻留运行。",
                keywords = "端口 冲突 bind address already in use 占用 2080 9090 修改 报错",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "常见疑难排查 FAQ",
                title = "IPv6 泄漏排查与国内双栈路由策略 (faqIpv6Leak)",
                badge = "隐私安全",
                desc = "在现代家庭宽带或 5G 蜂窝网络中，运营商普遍已分配 IPv6 公网地址。如果代理节点不支持 IPv6，或者客户端未正确拦截 IPv6 流量，某些浏览器在访问 Google/YouTube 时会尝试通过系统的 IPv6 物理网络直连，导致真实中国 IPv6 地址直接暴露给目标网站甚至因 GFW 阻断导致断流。\n解决方案：在 OwnBox「设置 - 核心设置」中，将「IPv6 路由」设置为“严格路由”或“仅代理 IPv4”，确保所有 IPv6 流量要么被安全封装进代理出站，要么被严格丢弃屏蔽，绝不旁路直连出境。",
                prosCons = "【利】100% 根绝真实中国大陆 IPv6 物理公网地址泄露，保护完全隐私；【弊】极少数只支持纯 IPv6 的冷门境外网站可能无法访问。",
                recommendation = "【推荐】境外节点多为 IPv4 环境，推荐在核心设置中配置 IPv6 严格保护，防范旁路泄露。",
                keywords = "ipv6 泄漏 泄露 隐私 双栈 严格路由 旁路 5g 宽带 防护",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "常见疑难排查 FAQ",
                title = "WebDAV 云端备份同步失败如何排查？ (faqWebdavFailed)",
                badge = "数据安全",
                desc = "常见失败原因与排查步骤：\n1. 【坚果云务必使用“应用授权密码”】：坚果云由于安全策略限制，WebDAV 严禁使用您的网页登录主密码！必须登录坚果云官网 -> 账户信息 -> 安全设置 -> 第三方应用管理 -> 添加应用密码，将生成的专用密码填入 OwnBox WebDAV 密码中；\n2. 【服务器 URL 格式需完整】：坚果云标准地址为 https://dav.jianguoyun.com/dav/（末尾斜杠请勿遗漏）；自建 Nextcloud 请使用 https://your-domain/remote.php/dav/files/username/；\n3. 【网络通畅性】：检查是否能正常 ping 通或浏览器访问 WebDAV 服务器域名。",
                prosCons = "【利】一次配置，终身多设备多端一键秒级备份与云端拉取；【弊】需要正确获取第三方应用密码。",
                recommendation = "【推荐】国内用户首选坚果云，免费且稳定，配置专用应用密码即可永续同步。",
                keywords = "webdav 备份 失败 坚果云 nextcloud 同步 密码 应用授权密码 错误 报错",
            )
        )
    }

    inner class DocsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int {
            return when (displayItems[position]) {
                is DocListItem.Header -> 0
                is DocListItem.Item -> 1
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                HeaderHolder(ItemDocHeaderBinding.inflate(layoutInflater, parent, false))
            } else {
                CardHolder(ItemDocCardBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = displayItems[position]) {
                is DocListItem.Header -> (holder as HeaderHolder).bind(item)
                is DocListItem.Item -> (holder as CardHolder).bind(item)
            }
        }

        override fun getItemCount(): Int = displayItems.size
    }

    inner class HeaderHolder(private val binding: ItemDocHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(header: DocListItem.Header) {
            binding.headerTitle.text = header.title
            binding.headerDesc.text = header.desc
        }
    }

    inner class CardHolder(private val binding: ItemDocCardBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DocListItem.Item) {
            binding.docItemTitle.text = item.title
            binding.docRecommendBadge.text = item.badge
            binding.docDesc.text = item.desc
            binding.docProsCons.text = item.prosCons
            binding.docRecommendation.text = item.recommendation
        }
    }
}
