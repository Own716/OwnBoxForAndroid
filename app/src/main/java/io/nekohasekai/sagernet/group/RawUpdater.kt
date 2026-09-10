package io.nekohasekai.sagernet.group

import android.annotation.SuppressLint
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SubscriptionFilterMode
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.parseSingBoxOutbound
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1Json
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.parseShadowsocksR
import io.nekohasekai.sagernet.fmt.snell.parseClashSnell
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.parseTrojanGo
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.XhttpExtraConverter
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.fmt.v2ray.normalizeXhttpMode
import io.nekohasekai.sagernet.fmt.v2ray.setTLS
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.parseWireGuardConfig
import io.nekohasekai.sagernet.fmt.wireguard.parseWireGuardEndpoints
import io.nekohasekai.sagernet.ktx.*
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.JavaUtil
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.yaml.snakeyaml.TypeDescription
import org.yaml.snakeyaml.Yaml
import androidx.core.net.toUri
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Suppress("EXPERIMENTAL_API_USAGE")
object RawUpdater : GroupUpdater() {

    @SuppressLint("Recycle")
    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    ) {

        val link = subscription.link
        var proxies: List<AbstractBean>
        if (link.startsWith("content://")) {
            val contentText = app.contentResolver.openInputStream(link.toUri())
                ?.bufferedReader()
                ?.readText()

            proxies = contentText?.let { parseRaw(contentText) }
                ?: error(app.getString(R.string.no_proxies_found_in_subscription))
        } else {

            val preferredUa = subscription.customUserAgent?.takeIf { it.isNotBlank() }
                ?: DataStore.defaultSubscriptionUserAgent

            // Fallback UA candidates in order: user preferred UA -> Singbox/1.14 -> clash-meta -> v2rayN/7.8.2 -> Throne/1.0.0 -> sing-box/1.14.0
            val candidateUas = linkedSetOf(
                preferredUa,
                "Singbox/1.14",
                "clash-meta",
                "v2rayN/7.8.2",
                "Throne/1.0.0",
                "sing-box/1.14.0"
            )

            var fetchedProxies: List<AbstractBean>? = null
            var lastRespHeaderUserinfo: String? = null
            var lastRespHeaderFilename: String? = null
            var lastRespHeaderProfileTitle: String? = null
            var lastException: Throwable? = null

            for (ua in candidateUas) {
                try {
                    val client = Libcore.newHttpClient().apply {
                        tryProxyOutbound()
                        when (DataStore.appTLSVersion) {
                            "1.3" -> restrictedTLS()
                        }
                    }
                    val request = client.newRequest().apply {
                        if (DataStore.allowInsecureOnRequest) {
                            allowInsecure()
                        }
                        setURL(subscription.link)
                        setUserAgent(ua)
                    }
                    val response = request.execute()

                    val userInfo = response.getHeader("Subscription-Userinfo")
                        ?: response.getHeader("subscription-userinfo")
                        ?: response.getHeader("Subscription-UserInfo")
                    val userInfoStr = Util.getStringBox(userInfo)
                    if (userInfoStr.isNotBlank()) {
                        lastRespHeaderUserinfo = userInfoStr
                    }
                    val remoteName = Util.getStringBox(response.getHeader("content-disposition"))
                    if (remoteName.isNotBlank()) {
                        lastRespHeaderFilename = remoteName
                    }
                    val profileTitle = Util.getStringBox(
                        response.getHeader("Profile-Title")
                            ?: response.getHeader("profile-title")
                            ?: response.getHeader("X-Profile-Title")
                            ?: response.getHeader("x-profile-title")
                    )
                    if (profileTitle.isNotBlank()) {
                        lastRespHeaderProfileTitle = profileTitle
                    }

                    val contentStr = Util.getStringBox(response.contentString)
                    val parsed = parseRaw(contentStr)
                    if (!parsed.isNullOrEmpty()) {
                        fetchedProxies = parsed
                        Logs.d("Subscription successfully parsed ${parsed.size} proxies with UA: $ua")
                        break
                    } else {
                        Logs.w("Subscription returned 0 proxies with UA: $ua, trying next candidate...")
                    }
                } catch (e: Throwable) {
                    Logs.w("Subscription download failed with UA: $ua: ${e.readableMessage}")
                    lastException = e
                }
            }

            if (fetchedProxies.isNullOrEmpty()) {
                throw lastException ?: IllegalStateException(app.getString(R.string.no_proxies_found))
            }

            proxies = fetchedProxies

            if (!lastRespHeaderUserinfo.isNullOrBlank()) {
                subscription.subscriptionUserinfo = lastRespHeaderUserinfo
            }

            // 自动提取机场名称（依次从 HTTP 响应头、URL query、二级域名、节点公共前缀提取）
            val extractedName = extractAirportName(
                subscription.link,
                lastRespHeaderFilename,
                lastRespHeaderProfileTitle,
                proxies
            )
            if (!extractedName.isNullOrBlank()) {
                val currentName = proxyGroup.name.orEmpty().trim()
                if (isDefaultGroupName(currentName)) {
                    proxyGroup.name = extractedName
                    Logs.i("RawUpdater: Auto-extracted airport name for group: $extractedName")
                }
            }
        }

        val proxiesMap = LinkedHashMap<String, AbstractBean>()
        for (proxy in proxies) {
            var index = 0
            var name = proxy.displayName()
            while (proxiesMap.containsKey(name)) {
                println("Exists name: $name")
                index++
                name = name.replace(" (${index - 1})", "")
                name = "$name ($index)"
                proxy.name = name
            }
            proxiesMap[proxy.displayName()] = proxy
        }
        proxies = proxiesMap.values.toList()

        if (subscription.forceResolve) forceResolve(proxies, proxyGroup.id)

        val filterMode = subscription.filterMode ?: SubscriptionFilterMode.DISABLED
        val filterRegex = subscription.filterRegex ?: ""
        if (filterMode != SubscriptionFilterMode.DISABLED && filterRegex.isNotBlank()) {
            val regex = filterRegex.toRegex()
            proxies = when (filterMode) {
                SubscriptionFilterMode.INCLUDE -> proxies.filter { regex.containsMatchIn(it.displayName()) }
                SubscriptionFilterMode.EXCLUDE -> proxies.filterNot { regex.containsMatchIn(it.displayName()) }
                else -> proxies
            }
            Logs.d("After filter (mode=$filterMode): ${proxies.size}")
        }

        val exists = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
        val duplicate = ArrayList<String>()
        if (subscription.deduplication) {
            Logs.d("Before deduplication: ${proxies.size}")
            val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
            val uniqueNames = HashMap<Protocols.Deduplication, String>()
            for (_proxy in proxies) {
                val proxy = Protocols.Deduplication(_proxy, _proxy.javaClass.toString())
                if (!uniqueProxies.add(proxy)) {
                    val index = uniqueProxies.indexOf(proxy)
                    if (uniqueNames.containsKey(proxy)) {
                        val name = uniqueNames[proxy]!!.replace(" ($index)", "")
                        if (name.isNotBlank()) {
                            duplicate.add("$name ($index)")
                            uniqueNames[proxy] = ""
                        }
                    }
                    duplicate.add(_proxy.displayName() + " ($index)")
                } else {
                    uniqueNames[proxy] = _proxy.displayName()
                }
            }
            uniqueProxies.retainAll(uniqueNames.keys)
            proxies = uniqueProxies.toList().map { it.bean }
        }

        Logs.d("New profiles: ${proxies.size}")

        val remainingExists = exists.toMutableList()
        val toInsert = mutableListOf<ProxyEntity>()
        val toUpdate = mutableListOf<ProxyEntity>()
        val added = mutableListOf<String>()
        val updated = mutableMapOf<String, String>()
        var userOrder = 1L
        var changed = 0

        for (bean in proxies) {
            val name = bean.displayName()
            val existingIndex = remainingExists.indexOfFirst { it.displayName() == name }
            if (existingIndex >= 0) {
                val entity = remainingExists.removeAt(existingIndex)
                val existsBean = entity.requireBean()
                // 更新订阅，保留自定义覆写设置
                bean.customOutboundJson = existsBean.customOutboundJson
                bean.customConfigJson = existsBean.customConfigJson
                when {
                    existsBean != bean -> {
                        changed++
                        entity.putBean(bean)
                        entity.userOrder = userOrder
                        toUpdate.add(entity)
                        updated[entity.displayName()] = name
                        Logs.d("Updated profile: $name")
                    }
                    entity.userOrder != userOrder -> {
                        changed++
                        entity.putBean(bean)
                        entity.userOrder = userOrder
                        toUpdate.add(entity)
                        Logs.d("Reordered profile: $name")
                    }
                    else -> {
                        Logs.d("Ignored profile: $name")
                    }
                }
            } else {
                changed++
                toInsert.add(
                    ProxyEntity(
                        groupId = proxyGroup.id,
                        userOrder = userOrder
                    ).apply {
                        putBean(bean)
                    }
                )
                added.add(name)
                Logs.d("Inserted profile: $name")
            }
            userOrder++
        }

        val toDelete = remainingExists
        changed += toDelete.size
        val deleted = toDelete.map { it.displayName() }

        Logs.d("toDelete profiles (orphans/removed/duplicates): ${toDelete.size}")
        Logs.d("toInsert profiles: ${toInsert.size}")
        Logs.d("toUpdate profiles: ${toUpdate.size}")

        toInsert.forEach {
            SagerDatabase.proxyDao.addProxy(it)
        }
        if (toUpdate.isNotEmpty()) {
            SagerDatabase.proxyDao.updateProxy(toUpdate).also {
                Logs.d("Updated profiles: $it")
            }
        }
        if (toDelete.isNotEmpty()) {
            SagerDatabase.proxyDao.deleteProxy(toDelete).also {
                Logs.d("Deleted profiles: $it")
            }
        }

        val existCount = SagerDatabase.proxyDao.countByGroup(proxyGroup.id).toInt()

        if (existCount != proxies.size) {
            Logs.e("Exist profiles: $existCount, new profiles: ${proxies.size}")
        }

        subscription.lastUpdated = (System.currentTimeMillis() / 1000).toInt()
        SagerDatabase.groupDao.updateGroup(proxyGroup)
        GroupManager.postUpdate(proxyGroup)
        finishUpdate(proxyGroup)

        userInterface?.onUpdateSuccess(
            proxyGroup, changed, added, updated, deleted, duplicate, byUser
        )
    }

    @Suppress("UNCHECKED_CAST")

    suspend fun parseRaw(text: String, fileName: String = ""): List<AbstractBean>? {

        val proxies = mutableListOf<AbstractBean>()

        if (text.contains("proxies:")) {

            // clash & meta

            try {

                val yaml = Yaml().apply {
                    addTypeDescription(TypeDescription(String::class.java, "str"))
                }.loadAs(text, Map::class.java)

                val globalClientFingerprint = yaml["global-client-fingerprint"]?.toString() ?: ""

                for (proxy in (yaml["proxies"] as? (List<Map<String, Any?>>) ?: error(
                    app.getString(R.string.no_proxies_found_in_file)
                ))) {
                    try {
                        val proxyType = (proxy["type"] as? String)?.lowercase() ?: continue
                        when (proxyType) {
                        "socks5" -> {
                            proxies.add(SOCKSBean().apply {
                                serverAddress = proxy["server"] as String
                                serverPort = proxy["port"].toString().toInt()
                                username = proxy["username"]?.toString()
                                password = proxy["password"]?.toString()
                                name = proxy["name"]?.toString()
                            })
                        }

                        "http" -> {
                            proxies.add(HttpBean().apply {
                                serverAddress = proxy["server"] as String
                                serverPort = proxy["port"].toString().toInt()
                                username = proxy["username"]?.toString()
                                password = proxy["password"]?.toString()
                                setTLS(proxy["tls"]?.toString() == "true")
                                sni = proxy["sni"]?.toString()
                                name = proxy["name"]?.toString()
                                allowInsecure = proxy["skip-cert-verify"]?.toString() == "true"
                            })
                        }

                        "ss" -> {
                            val ssPlugin = mutableListOf<String>()
                            if (proxy.contains("plugin")) {
                                val opts = proxy["plugin-opts"] as Map<String, Any?>
                                when (proxy["plugin"]) {
                                    "obfs" -> {
                                        ssPlugin.apply {
                                            add("obfs-local")
                                            add("obfs=" + (opts["mode"]?.toString() ?: ""))
                                            add("obfs-host=" + (opts["host"]?.toString() ?: ""))
                                        }
                                    }

                                    "v2ray-plugin" -> {
                                        ssPlugin.apply {
                                            add("v2ray-plugin")
                                            add("mode=" + (opts["mode"]?.toString() ?: ""))
                                            if (opts["mode"]?.toString() == "true") add("tls")
                                            add("host=" + (opts["host"]?.toString() ?: ""))
                                            add("path=" + (opts["path"]?.toString() ?: ""))
                                            if (opts["mux"]?.toString() == "true") add("mux=8")
                                        }
                                    }
                                }
                            }
                            proxies.add(ShadowsocksBean().apply {
                                serverAddress = proxy["server"] as String
                                serverPort = proxy["port"].toString().toInt()
                                password = proxy["password"]?.toString()
                                method = clashCipher(proxy["cipher"] as String)
                                plugin = ssPlugin.joinToString(";")
                                name = proxy["name"]?.toString()
                            })
                        }

                        "ssr" -> {
                            proxies.add(ShadowsocksRBean().apply {
                                for (opt in proxy) {
                                    if (opt.value == null) continue
                                    when (opt.key) {
                                        "name" -> name = opt.value.toString()
                                        "server" -> serverAddress = opt.value as String
                                        "port" -> serverPort = opt.value.toString().toInt()
                                        "cipher" -> method = clashCipher(opt.value as String)
                                        "password" -> password = opt.value.toString()
                                        "obfs" -> obfs = opt.value as String
                                        "protocol" -> protocol = opt.value as String
                                        "obfs-param" -> obfsParam = opt.value.toString()
                                        "protocol-param" -> protocolParam = opt.value.toString()
                                    }
                                }
                            })
                        }

                        "vmess", "vless", "trojan", "xhttp", "splithttp" -> {
                            val bean = when (proxyType) {
                                "vmess" -> VMessBean()
                                "vless" -> VMessBean().apply {
                                    alterId = -1 // make it VLESS
                                    packetEncoding = 2 // clash meta default XUDP
                                }
                                "xhttp", "splithttp" -> VMessBean().apply {
                                    alterId = -1
                                    type = "xhttp"
                                    packetEncoding = 2
                                }
                                "trojan" -> TrojanBean().apply {
                                    security = "tls"
                                }

                                else -> error("impossible")
                            }

                            bean.serverAddress = proxy["server"]?.toString() ?: continue
                            bean.serverPort = proxy["port"]?.toString()?.toIntOrNull() ?: continue

                            for (opt in proxy) {
                                when (opt.key) {
                                    "name" -> bean.name = opt.value?.toString()
                                    "password" -> if (bean is TrojanBean) bean.password =
                                        opt.value?.toString()

                                    "uuid" -> if (bean is VMessBean) bean.uuid =
                                        opt.value?.toString()

                                    "alterId" -> if (bean is VMessBean && !bean.isVLESS) bean.alterId =
                                        opt.value?.toString()?.toIntOrNull()

                                    "cipher" -> if (bean is VMessBean && !bean.isVLESS) bean.encryption =
                                        (opt.value as? String)

                                    "flow" -> if (bean is VMessBean && bean.isVLESS) {
                                        (opt.value as? String)?.let {
                                            if (it.contains("xtls-rprx-vision")) {
                                                bean.encryption = "xtls-rprx-vision"
                                            }
                                        }
                                    }

                                    "encryption" -> if (bean is VMessBean && bean.isVLESS) {
                                        bean.vlessEncryption = opt.value?.toString() ?: ""
                                    }

                                    "packet-encoding" -> if (bean is VMessBean) {
                                        bean.packetEncoding = when ((opt.value as? String)) {
                                            "packetaddr" -> 1
                                            "xudp" -> 2
                                            else -> 0
                                        }
                                    }

                                    "tls" -> if (bean is VMessBean) {
                                        bean.security =
                                            if (opt.value as? Boolean == true) "tls" else ""
                                    }

                                    "servername", "sni" -> bean.sni = opt.value?.toString()

                                    "alpn" -> bean.alpn =
                                        (opt.value as? List<Any>)?.joinToString("\n")

                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value as? Boolean == true

                                    "client-fingerprint" -> bean.utlsFingerprint =
                                        opt.value as String

                                    "reality-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (realityOpt in it) {
                                            bean.security = "tls"

                                            when (realityOpt.key) {
                                                "public-key" -> bean.realityPubKey =
                                                    realityOpt.value?.toString()

                                                "short-id" -> bean.realityShortId =
                                                    realityOpt.value?.toString()
                                            }
                                        }
                                    }

                                    "network" -> {
                                        when (opt.value) {
                                            "h2", "http" -> bean.type = "http"
                                            "ws", "grpc" -> bean.type = opt.value as String
                                            "xhttp", "splithttp" -> bean.type = "xhttp"
                                        }
                                    }

                                    "ws-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (wsOpt in it) {
                                            when (wsOpt.key) {
                                                "headers" -> (wsOpt.value as? Map<Any, Any?>)?.forEach { (key, value) ->
                                                    when (key.toString().lowercase()) {
                                                        "host" -> {
                                                            bean.host = value?.toString()
                                                        }
                                                    }
                                                }

                                                "path" -> {
                                                    bean.path = wsOpt.value?.toString()
                                                }

                                                "max-early-data" -> {
                                                    bean.wsMaxEarlyData =
                                                        wsOpt.value?.toString()?.toIntOrNull()
                                                }

                                                "early-data-header-name" -> {
                                                    bean.earlyDataHeaderName =
                                                        wsOpt.value?.toString()
                                                }

                                                "v2ray-http-upgrade" -> {
                                                    if (wsOpt.value as? Boolean == true) {
                                                        bean.type = "httpupgrade"
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    "h2-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (h2Opt in it) {
                                            when (h2Opt.key) {
                                                "host" -> bean.host =
                                                    (h2Opt.value as? List<Any>)?.joinToString("\n")

                                                "path" -> bean.path = h2Opt.value?.toString()
                                            }
                                        }
                                    }

                                    "http-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (httpOpt in it) {
                                            when (httpOpt.key) {
                                                "path" -> bean.path =
                                                    (httpOpt.value as? List<Any>)?.joinToString("\n")

                                                "headers" -> {
                                                    (httpOpt.value as? Map<Any, List<Any>>)?.forEach { (key, value) ->
                                                        when (key.toString().lowercase()) {
                                                            "host" -> {
                                                                bean.host = value.joinToString("\n")
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    "grpc-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (grpcOpt in it) {
                                            when (grpcOpt.key) {
                                                "grpc-service-name" -> bean.path =
                                                    grpcOpt.value?.toString()
                                            }
                                        }
                                    }

                                    "xhttp-opts", "splithttp-opts" -> {
                                        bean.type = "xhttp"
                                        (opt.value as? Map<String, Any?>)?.also { xhttpOpts ->
                                            xhttpOpts["host"]?.toString()?.let {
                                                bean.host = it
                                            }
                                            xhttpOpts["path"]?.toString()?.let {
                                                bean.path = it
                                            }
                                            xhttpOpts["mode"]?.toString()?.let {
                                                bean.xhttpMode = normalizeXhttpMode(it)
                                            }

                                            bean.xhttpExtra = XhttpExtraConverter.clashToSingBox(xhttpOpts)
                                        }
                                    }

                                    "smux" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (smuxOpt in it) {
                                            when (smuxOpt.key) {
                                                "enabled" -> bean.enableMux =
                                                    smuxOpt.value.toString() == "true"

                                                "max-streams" -> bean.muxConcurrency =
                                                    smuxOpt.value.toString().toInt()

                                                "padding" -> bean.muxPadding =
                                                    smuxOpt.value.toString() == "true"
                                            }
                                        }
                                    }

                                    "ech-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (echOpt in it) {
                                            when (echOpt.key) {
                                                "enable" -> bean.enableECH =
                                                    echOpt.value.toString() == "true"

                                                "config" -> bean.echConfig =
                                                    echOpt.value?.toString()
                                            }
                                        }
                                    }
                                }
                            }
                            proxies.add(bean)
                        }

                        "anytls" -> {
                            val bean = AnyTLSBean()
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value as String
                                    "port" -> bean.serverPort = opt.value.toString().toInt()
                                    "password" -> bean.password = opt.value.toString()
                                    "client-fingerprint" -> bean.utlsFingerprint =
                                        opt.value as String

                                    "sni" -> bean.sni = opt.value.toString()
                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value.toString() == "true"

                                    "alpn" -> {
                                        val alpn = (opt.value as? (List<String>))
                                        bean.alpn = alpn?.joinToString("\n")
                                    }
                                    "reality-pub-key", "public-key" -> bean.realityPubKey =
                                        opt.value.toString()
                                    "reality-short-id", "short-id" -> bean.realityShortId =
                                        opt.value.toString()
                                }
                            }
                            proxies.add(bean)
                        }

                        "wireguard" -> {
                            val peers = proxy["peers"] as? List<Map<String, Any?>>
                            val configToUse = peers?.firstOrNull() ?: proxy

                            val bean = WireGuardBean().apply {
                                name = proxy["name"].toString()

                                for ((key, value) in configToUse) {
                                    when (key.replace("_", "-")) {
                                        "server" -> serverAddress = value.toString()
                                        "port" -> serverPort = value.toString().toIntOrNull() ?: 0
                                        "mtu" -> mtu = value.toString().toIntOrNull() ?: 0
                                        "ip" -> {
                                            val ipValue = value.toString()
                                            localAddress = if (!ipValue.contains("/")) {
                                                "$ipValue/32"
                                            } else {
                                                ipValue
                                            }
                                        }
                                        "ipv6" -> {
                                            val ipv6Value = value.toString()
                                            val processedIPv6Value = if (!ipv6Value.contains("/")) {
                                                "$ipv6Value/128"
                                            } else {
                                                ipv6Value
                                            }
                                            if (localAddress.isNullOrEmpty()) {
                                                localAddress = processedIPv6Value
                                            } else {
                                                localAddress += "\n$processedIPv6Value"
                                            }
                                        }
                                        "private-key" -> privateKey = value.toString()
                                        "public-key" -> peerPublicKey = value.toString()
                                        "pre-shared-key", "preshared-key" -> peerPreSharedKey = value.toString()
                                        "reserved" -> {
                                            val reservedValue = value
                                            when (reservedValue) {
                                                is List<*> -> {
                                                    if (reservedValue.size == 1) {
                                                        reserved = reservedValue[0].toString().replace("[\\[\\] ]".toRegex(), "")
                                                    } else {
                                                        reserved = reservedValue.joinToString("\n") { it.toString() }
                                                    }
                                                }
                                                else -> {
                                                    reserved = reservedValue.toString().replace("[\\[\\] ]".toRegex(), "")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            proxies.add(bean)
                        }

                        "hysteria" -> {
                            val bean = HysteriaBean()
                            bean.protocolVersion = 1
                            var hopPorts = ""
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value as String
                                    "port" -> bean.serverPorts = opt.value.toString()
                                    "ports" -> hopPorts = opt.value.toString()

                                    "obfs" -> bean.obfuscation = opt.value.toString()

                                    "auth-str" -> {
                                        bean.authPayloadType = HysteriaBean.TYPE_STRING
                                        bean.authPayload = opt.value.toString()
                                    }

                                    "sni" -> bean.sni = opt.value.toString()

                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value.toString() == "true"

                                    "up" -> bean.uploadMbps =
                                        opt.value.toString().substringBefore(" ").toIntOrNull()
                                            ?: 100

                                    "down" -> bean.downloadMbps =
                                        opt.value.toString().substringBefore(" ").toIntOrNull()
                                            ?: 100

                                    "recv-window-conn" -> bean.connectionReceiveWindow =
                                        opt.value.toString().toIntOrNull() ?: 0

                                    "recv-window" -> bean.streamReceiveWindow =
                                        opt.value.toString().toIntOrNull() ?: 0

                                    "disable-mtu-discovery" -> bean.disableMtuDiscovery =
                                        opt.value.toString() == "true" || opt.value.toString() == "1"

                                    "alpn" -> {
                                        val alpn = (opt.value as? (List<String>))
                                        bean.alpn = alpn?.joinToString("\n") ?: "h3"
                                    }
                                }
                            }
                            if (hopPorts.isNotBlank()) {
                                bean.serverPorts = hopPorts
                            }
                            proxies.add(bean)
                        }

                        "hysteria2" -> {
                            val bean = HysteriaBean()
                            bean.protocolVersion = 2
                            var hopPorts = ""
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value as String
                                    "port" -> bean.serverPorts = opt.value.toString()
                                    "ports" -> hopPorts = opt.value.toString()

                                    "obfs-password" -> bean.obfuscation = opt.value.toString()

                                    "password" -> bean.authPayload = opt.value.toString()

                                    "sni" -> bean.sni = opt.value.toString()

                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value.toString() == "true"

                                    "up" -> bean.uploadMbps =
                                        opt.value.toString().substringBefore(" ").toIntOrNull() ?: 0

                                    "down" -> bean.downloadMbps =
                                        opt.value.toString().substringBefore(" ").toIntOrNull() ?: 0
                                }
                            }
                            if (hopPorts.isNotBlank()) {
                                bean.serverPorts = hopPorts
                            }
                            proxies.add(bean)
                        }

                        "tuic" -> {
                            val bean = TuicBean()
                            var ip = ""
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value.toString()
                                    "ip" -> ip = opt.value.toString()
                                    "port" -> bean.serverPort = opt.value.toString().toInt()

                                    "token" -> {
                                        bean.protocolVersion = 4
                                        bean.token = opt.value.toString()
                                    }

                                    "uuid" -> bean.uuid = opt.value.toString()

                                    "password" -> bean.token = opt.value.toString()

                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value.toString() == "true"

                                    "disable-sni" -> bean.disableSNI =
                                        opt.value.toString() == "true"

                                    "reduce-rtt" -> bean.reduceRTT =
                                        opt.value.toString() == "true"

                                    "sni" -> bean.sni = opt.value.toString()

                                    "alpn" -> {
                                        val alpn = (opt.value as? (List<String>))
                                        bean.alpn = alpn?.joinToString("\n")
                                    }

                                    "congestion-controller" -> bean.congestionController =
                                        opt.value.toString()

                                    "udp-relay-mode" -> bean.udpRelayMode = opt.value.toString()

                                }
                            }
                            if (ip.isNotBlank()) {
                                bean.serverAddress = ip
                                if (bean.sni.isNullOrBlank() && !bean.serverAddress.isNullOrBlank() && !bean.serverAddress.isIpAddress()) {
                                    bean.sni = bean.serverAddress
                                }
                            }
                            proxies.add(bean)
                        }

                        "snell" -> {
                            val bean = parseClashSnell(proxy)
                            proxies.add(bean)
                        }
                    }
                } catch (e: Throwable) {
                    Logs.w(e)
                }
            }

                // Fix ent
                proxies.forEach {
                    it.initializeDefaultValues()
                    if (it is StandardV2RayBean) {
                        // 1. SNI
                        if (it.isTLS() && it.sni.isNullOrBlank() && !it.host.isNullOrBlank() && !it.host.isIpAddress()) {
                            it.sni = it.host
                        }
                        // 2. globalClientFingerprint
                        if (!it.realityPubKey.isNullOrBlank() && it.utlsFingerprint.isNullOrBlank()) {
                            it.utlsFingerprint = globalClientFingerprint
                            if (it.utlsFingerprint.isNullOrBlank()) it.utlsFingerprint = "chrome"
                        }
                    }
                }
                if (proxies.isNotEmpty()) {
                    return proxies.deduplicateProxies()
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
        } else if (text.contains("[Interface]")) {
            // wireguard / amneziawg
            try {
                val isAwg = text.contains("Jc", ignoreCase = true) || text.contains("Jmin", ignoreCase = true) || text.contains("S1", ignoreCase = true) || text.contains("H1", ignoreCase = true)
                proxies.addAll(parseWireGuardConfig(text).map {
                    val prefix = if (isAwg) "[AWG-Compat] " else ""
                    if (fileName.isNotBlank()) it.name = prefix + fileName.removeSuffix(".conf")
                    else if (isAwg && !it.name.startsWith("[AWG-Compat]")) it.name = prefix + (it.name.ifBlank { "WireGuard" })
                    it
                })
                if (proxies.isNotEmpty()) {
                    return proxies.deduplicateProxies()
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        try {
            val json = JSONTokener(text).nextValue()
            val jsonProxies = parseJSON(json)
            if (!jsonProxies.isNullOrEmpty()) {
                return jsonProxies.deduplicateProxies()
            }
        } catch (ignored: Exception) {
        }

        try {
            val base64Decoded = text.decodeBase64UrlSafe()
            val parsed = parseProxies(base64Decoded)
            if (!parsed.isNullOrEmpty()) {
                return parsed.deduplicateProxies()
            }
        } catch (ignored: Exception) {
        }

        try {
            val parsed = parseProxies(text)
            if (!parsed.isNullOrEmpty()) {
                return parsed.deduplicateProxies()
            }
        } catch (e: SubscriptionFoundException) {
            throw e
        } catch (ignored: Exception) {
        }

        return null
    }

    fun clashCipher(cipher: String): String {
        return when (cipher) {
            "dummy" -> "none"
            else -> cipher
        }
    }

    fun parseJSON(json: Any): List<AbstractBean> {
        val proxies = ArrayList<AbstractBean>()

        if (json is JSONObject) {
            when {
                json.has("server") && (json.has("up") || json.has("up_mbps")) -> {
                    return listOf(json.parseHysteria1Json())
                }

                json.has("method") && json.has("obfs") && json.has("protocol") -> {
                    return listOf(json.parseShadowsocksR())
                }

                json.has("method") -> {
                    return listOf(json.parseShadowsocks())
                }

                json.has("remote_addr") -> {
                    return listOf(json.parseTrojanGo())
                }

                json.has("outbounds") || json.has("endpoints") -> {
                    val outbounds = json.optJSONArray("outbounds")
                        ?: JSONArray()
                    return outbounds
                        .filterIsInstance<JSONObject>()
                        .mapNotNull {
                            val ty = it.getStr("type")
                            if (ty == null || ty == "" ||
                                ty == "dns" || ty == "block" || ty == "direct" || ty == "selector" || ty == "urltest"
                            ) {
                                null
                            } else {
                                it
                            }
                        }.map {
                            // 优先将 sing-box outbound 还原为原生协议 Bean，
                            // 不支持的类型或解析失败时回退为自定义 JSON
                            runCatching { parseSingBoxOutbound(it) }.getOrNull()
                                ?: ConfigBean().apply {
                                    applyDefaultValues()
                                    type = 1
                                    config = it.toStringPretty()
                                    name = it.getStr("tag")
                                }
                        }
                        .plus(runCatching {
                            parseWireGuardEndpoints(
                                JavaUtil.gson.fromJson(json.toString(), com.google.gson.JsonObject::class.java)
                            )
                        }.getOrDefault(emptyList()))
                }

                json.has("server") && json.has("server_port") -> {
                    return listOf(ConfigBean().applyDefaultValues().apply {
                        type = 1
                        config = json.toStringPretty()
                    })
                }
            }
        } else {
            json as JSONArray
            json.forEach { _, it ->
                if (isJsonObjectValid(it)) {
                    proxies.addAll(parseJSON(it))
                }
            }
        }

        proxies.forEach { it.initializeDefaultValues() }
        return proxies
    }

    fun isDefaultGroupName(name: String?): Boolean {
        val currentName = name.orEmpty().trim()
        if (currentName.isEmpty()) return true
        val defaultKeywords = listOf(
            "My group",
            "我的分组",
            "Group",
            "分组",
            "Subscription",
            "订阅",
            "Default",
            "默认",
            "Ungrouped",
            "未分组"
        )
        if (defaultKeywords.any { currentName.equals(it, ignoreCase = true) }) return true
        if (currentName.startsWith("Subscription #") ||
            currentName.startsWith("订阅 #") ||
            currentName.startsWith("Group #") ||
            currentName.startsWith("分组 #")
        ) return true
        return false
    }

    fun extractAirportName(
        subscriptionLink: String,
        filenameHeader: String? = null,
        profileTitleHeader: String? = null,
        proxies: List<AbstractBean> = emptyList()
    ): String? {
        // 1. HTTP 响应头
        // 1.1 content-disposition 中的 filename
        if (!filenameHeader.isNullOrBlank()) {
            val decoded = Util.decodeFilename(filenameHeader).trim()
            val cleanName = decoded.replace(Regex("\\.(ya?ml|txt|json|conf|sub)$", RegexOption.IGNORE_CASE), "").trim()
            if (cleanName.isNotBlank() && !cleanName.equals("subscription", ignoreCase = true) && !cleanName.equals("clash", ignoreCase = true)) {
                return cleanName
            }
        }

        // 1.2 profile-title / x-profile-title
        if (!profileTitleHeader.isNullOrBlank()) {
            var title = profileTitleHeader.trim()
            if (title.startsWith("base64:", ignoreCase = true)) {
                try {
                    val decodedBytes = android.util.Base64.decode(title.substring(7), android.util.Base64.DEFAULT)
                    title = String(decodedBytes, Charsets.UTF_8).trim()
                } catch (_: Throwable) {
                }
            } else {
                try {
                    title = java.net.URLDecoder.decode(title, "UTF-8").trim()
                } catch (_: Throwable) {
                }
            }
            if (title.isNotBlank()) {
                return title
            }
        }

        // 2. URL Query 参数: name 或 title
        try {
            val httpUrl = subscriptionLink.toHttpUrlOrNull()
            if (httpUrl != null) {
                val nameParam = httpUrl.queryParameter("name")?.trim()
                if (!nameParam.isNullOrBlank()) return nameParam
                val titleParam = httpUrl.queryParameter("title")?.trim()
                if (!titleParam.isNullOrBlank()) return titleParam
            }
        } catch (_: Throwable) {
        }

        // 3. 订阅链接二级域名（过滤常用 CDN/OSS/平台域名）
        try {
            val httpUrl = subscriptionLink.toHttpUrlOrNull()
            val host = httpUrl?.host?.trim()
            if (!host.isNullOrBlank() && !host.isIpAddress() && host != "localhost") {
                val commonDomains = setOf(
                    "github.com", "raw.githubusercontent.com", "github.io", "gitlab.com", "gitlab.io",
                    "workers.dev", "pages.dev", "cloudflare.com", "cloudfront.net", "fastly.net",
                    "vercel.app", "netlify.app", "render.com", "herokuapp.com", "jsdelivr.net",
                    "aliyuncs.com", "myqcloud.com", "amazonaws.com", "azure.com", "google.com"
                )
                val isCommon = commonDomains.any { host.equals(it, ignoreCase = true) || host.endsWith(".$it", ignoreCase = true) }
                if (!isCommon) {
                    val parts = host.split(".")
                    if (parts.size >= 2) {
                        val candidate = if (parts.size >= 3) parts[parts.size - 2] else parts[0]
                        val genericNames = setOf("sub", "subscribe", "subscription", "api", "node", "link", "app", "v2", "clash")
                        if (!genericNames.contains(candidate.lowercase()) && candidate.length >= 2) {
                            return candidate
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }

        // 4. 提取全部节点共有的前缀标识（例如 [极速云] 或 【极速云】）
        if (proxies.isNotEmpty()) {
            val names = proxies.map { it.displayName().trim() }.filter { it.isNotBlank() }
            if (names.isNotEmpty()) {
                val bracketPattern = Regex("^\\[([^\\]]+)\\]|^【([^】]+)】|^\\(([^\\)]+)\\)")
                val firstMatch = bracketPattern.find(names.first())
                if (firstMatch != null) {
                    val tag = (firstMatch.groups[1] ?: firstMatch.groups[2] ?: firstMatch.groups[3])?.value?.trim()
                    if (!tag.isNullOrBlank() && names.all { it.startsWith("[${tag}]") || it.startsWith("【${tag}】") || it.startsWith("(${tag})") }) {
                        return tag
                    }
                }

                val first = names.first()
                for (sep in listOf(" - ", " | ", "-", "|", "_")) {
                    if (first.contains(sep)) {
                        val candidatePrefix = first.substringBefore(sep).trim()
                        if (candidatePrefix.length in 2..20 && names.all { it.startsWith(candidatePrefix) }) {
                            return candidatePrefix
                        }
                    }
                }
            }
        }

        return null
    }

}
