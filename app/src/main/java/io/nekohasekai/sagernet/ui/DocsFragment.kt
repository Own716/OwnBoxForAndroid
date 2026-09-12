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
                badge = "推荐: 开启",
                desc = "在通知中也显示不经过代理的流量速度。提示：该功能是在“手机通知中心”里显示直连速度，方便随时观察直连应用的后台流量动向。",
                prosCons = "【利】在手机下拉通知中心中，不仅能实时查看代理出站网速，还能同步洞察国内直连应用（如微信、网银、国内视频等）的真实数据吞吐，一览全局后台流量，防范个别应用在后台偷跑流量；【弊】通知栏网速展示区域文本长度略微增加。",
                recommendation = "【最稳推荐：开启】在通知中心全景感知手机双向网络吞吐，防范后台偷跑。",
                keywords = "直连速度 速度 通知中心 通知栏 showDirectSpeed 网速 偷跑",
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
                desc = "在 Android 系统下拉通知栏的 VPN 常驻通知中，同步标明当前连接节点所属的订阅分组或合集名称。",
                prosCons = "【利】下拉通知即可确认当前节点来自哪个机场或自建分组；【弊】若分组名称极长在窄屏设备上可能略微挤占宽度。",
                recommendation = "【最稳推荐：开启】方便随时确认当前使用的代理服务归属。",
                keywords = "通知栏 分组 机场 订阅",
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
                title = "动态取色与纯色主题 (useSystemTheme / appTheme / amoledTheme)",
                badge = "推荐: 按个人偏好",
                desc = "支持 Android 12+ Monet 莫奈原生壁纸动态取色、自定义 HEX 主题色、纯白白底模式及 OLED/AMOLED 纯黑夜间模式。",
                prosCons = "【利】个性化视觉体验，纯白清爽明亮，AMOLED 纯黑在发光二极管屏幕上深度节能；【弊】无负面影响。",
                recommendation = "【最稳推荐：自由选用】对网络核心与底层代理协议零影响，按视觉喜好设定即可。",
                keywords = "主题 莫奈 动态取色 纯白 amoled 夜间 颜色",
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
                badge = "推荐: gVisor (默认)",
                desc = "指定 VPN 虚拟网卡用户态网络协议栈的底层实现算法（gVisor / System / Mixed）。",
                prosCons = "【利】gVisor 完全运行在用户态，沙箱隔离严密，对弱网丢包、乱序抗性极强，兼容性 100%；【弊】在大吞吐极限极限跑分下 CPU 占用比系统栈多 ~3%。",
                recommendation = "【最稳推荐：保持默认 gVisor】经无数设备长期实测，gVisor 是长效稳定不掉线、无任何系统崩溃风险的终极方案。",
                keywords = "tun gvisor system mixed 协议栈 网络栈",
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
                recommendation = "【最稳推荐：开启】Own 内部已内置单栈防穿透过滤，开启可保障分流准确性最大化。",
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

        // 9. 侧边栏网络工具
        allItems.add(
            DocListItem.Header(
                "9. 侧边栏网络工具 (Network Tools)",
                "侧边栏一键全息体检工具集（提示：受公网 API 波动及多方数据库差异影响，所有网络工具测试结果仅供参考）"
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "侧边栏网络工具",
                title = "连通性测试 (tool_connectivity_test)",
                badge = "仅供参考",
                desc = "对当前激活的网络连接进行全链路综合体检：依次测试网络接口状态、本地 DNS 解析、核心出站代理隧道、国内知名服务（百度、B站、微信）以及海外骨干节点（Google、Cloudflare、GitHub）的连通性。醒目标注：【本功能仅供参考】！各探针测试易受公网接口限流与机场防火墙干扰，结果仅供参考，不代表网络绝对故障。",
                prosCons = "【利】一键分段诊断是手机无网、DNS 污染、代理断流还是外部网站异常，大幅缩短排错耗时；【弊】部分机场安全策略可能主动拦截特定自动化探针，偶发局部标红误报。",
                recommendation = "【最稳推荐：测试结果仅供参考，不作为绝对网络评判标准】若测试中个别项显示异常但实际网页、视频与 App 访问通畅，以实际日常体验为准。",
                keywords = "连通性 连通性测试 网络诊断 诊断 体检 仅供参考 tool_connectivity_test",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "侧边栏网络工具",
                title = "流媒体解锁检测 (tool_media_unlock)",
                badge = "仅供参考",
                desc = "批量探测当前代理出站 IP 针对全球主流流媒体与 AI 服务平台的版权区域解锁状态（涵盖 Netflix 原生/自制剧解锁、YouTube Premium 区域、Disney+、ChatGPT、TikTok 等）。醒目标注：【本功能仅供参考】！各大版权方风控策略动态变化，本工具仅供参考。",
                prosCons = "【利】一目了然确认节点能否观看 Netflix 非自制剧、是否支持流畅注册与使用 ChatGPT，避免错选不支持的节点；【弊】流媒体平台封锁规则频繁更迭（结合客户端设备指纹、网络抖动等），单一探针难以 100% 覆盖所有客户端表现。",
                recommendation = "【最稳推荐：测试结果仅供参考】若检测显示未解锁但官方客户端能正常播放高清视频或对话，以实际客户端使用为准。",
                keywords = "流媒体 解锁 netflix youtube premium disney chatgpt tiktok 仅供参考 tool_media_unlock",
            )
        )
        allItems.add(
            DocListItem.Item(
                category = "侧边栏网络工具",
                title = "IP 纯净度检测 (tool_ip_purity)",
                badge = "仅供参考",
                desc = "深度查询当前出站落地 IP 的机房/家宽原生属性、ASN 运营商归属、黑名单历史记录、欺诈评分（Fraud Score）与风控等级。醒目标注：【本功能仅供参考】！各大商业风控数据库算法差异巨大，本功能仅供参考。",
                prosCons = "【利】获知节点 IP 属于机房托管（Hosting/DataCenter）还是原生住宅宽带（Residential），辅助评估注册海外严控服务的成功率；【弊】不同风控库打分规则不一，机房 IP 绝不等于无法正常科学上网。",
                recommendation = "【最稳推荐：测试结果仅供参考，切勿盲目追求 0 欺诈分】只要节点日常访问网页流畅、无频繁人机验证验证码即可安心使用。",
                keywords = "ip 纯净度 欺诈分 风险 家宽 机房 原生 仅供参考 tool_ip_purity",
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
