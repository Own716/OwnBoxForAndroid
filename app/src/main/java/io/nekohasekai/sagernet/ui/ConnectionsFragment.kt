package io.nekohasekai.sagernet.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ConnectionsFragment : ToolbarFragment(R.layout.layout_connections) {

    data class ConnectionItem(
        val id: String,
        val appName: String,
        val packageName: String,
        val icon: Drawable?,
        val network: String,
        val host: String,
        val rule: String,
        val upload: Long,
        val download: Long,
        val chains: String,
    )

    private val httpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private lateinit var tvActiveCount: TextView
    private lateinit var btnCloseAll: MaterialButton
    private lateinit var etSearch: EditText
    private lateinit var recyclerConnections: RecyclerView
    private lateinit var tvEmpty: TextView

    private val adapter = ConnectionsAdapter()
    private val appInfoCache = ConcurrentHashMap<String, Pair<String, Drawable?>>()
    private var allConnections = listOf<ConnectionItem>()
    private var pollJob: Job? = null
    private var filterKeyword = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (isToolbarInitialized) {
            toolbar.setTitle(R.string.connections_title)
        }

        tvActiveCount = view.findViewById(R.id.tv_active_count)
        btnCloseAll = view.findViewById(R.id.btn_close_all)
        etSearch = view.findViewById(R.id.et_search)
        recyclerConnections = view.findViewById(R.id.recycler_connections)
        tvEmpty = view.findViewById(R.id.tv_empty)

        recyclerConnections.layoutManager = LinearLayoutManager(requireContext())
        recyclerConnections.adapter = adapter
        ViewCompat.setOnApplyWindowInsetsListener(recyclerConnections, ListListener)

        btnCloseAll.setOnClickListener {
            val service = currentService()
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                if (service != null) {
                    runCatching { service.closeAllActiveConnections() }
                } else if (DataStore.enableClashAPI || DataStore.allowAccess) {
                    runCatching {
                        val req = buildClashRequest("http://127.0.0.1:9090/connections")
                            .delete()
                            .build()
                        httpClient.newCall(req).execute().close()
                    }
                }
                fetchConnections()
            }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterKeyword = s?.toString()?.trim() ?: ""
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    private fun currentService(): ISagerNetService? =
        (activity as? MainActivity)?.connection?.service

    private fun buildClashRequest(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        val secret = DataStore.clashApiSecret
        if (secret.isNotBlank()) {
            builder.header("Authorization", "Bearer $secret")
        }
        return builder
    }

    private fun startPolling() {
        stopPolling()
        if (!DataStore.enableConnectionsMonitor) return
        pollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && DataStore.enableConnectionsMonitor) {
                fetchConnections()
                delay(1500)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun resolveAppDisplay(
        pm: PackageManager,
        rawPackageName: String,
        uid: Int,
    ): Triple<String, String, Drawable?> {
        var pkg = rawPackageName.trim()
        if (pkg.isEmpty() && uid >= 0) {
            pkg = runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull().orEmpty()
        }
        if (pkg.isEmpty()) {
            val fallbackName = if (uid >= 0) "UID $uid" else "系统 / 本机内核"
            return Triple(fallbackName, "", null)
        }
        appInfoCache[pkg]?.let { cached ->
            return Triple(cached.first, pkg, cached.second)
        }
        val label = runCatching {
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)
        val icon = runCatching {
            pm.getApplicationIcon(pkg)
        }.getOrNull()
        appInfoCache[pkg] = label to icon
        return Triple(label, pkg, icon)
    }

    private fun parseChainsArray(arr: JSONArray?, reverse: Boolean, fallback: String): String {
        if (arr != null && arr.length() > 0) {
            val list = ArrayList<String>(arr.length())
            if (reverse) {
                for (i in arr.length() - 1 downTo 0) {
                    val tag = arr.optString(i).trim()
                    if (tag.isNotEmpty()) list.add(tag)
                }
            } else {
                for (i in 0 until arr.length()) {
                    val tag = arr.optString(i).trim()
                    if (tag.isNotEmpty()) list.add(tag)
                }
            }
            if (list.isNotEmpty()) return list.joinToString(" » ")
        }
        return fallback.takeIf { it.isNotBlank() } ?: "直连"
    }

    private fun stripProcessSuffix(rawPath: String): String {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) return ""
        val idx = trimmed.lastIndexOf(" (")
        val cleaned = if (idx > 0 && trimmed.endsWith(")")) {
            trimmed.substring(0, idx).trim()
        } else {
            trimmed
        }
        return if (cleaned.all { it.isDigit() }) "" else cleaned
    }

    private suspend fun fetchConnections() {
        if (!DataStore.serviceState.started) {
            withContext(Dispatchers.Main) {
                if (!isAdded || view == null) return@withContext
                allConnections = emptyList()
                tvActiveCount.text = "活跃连接: 0"
                tvEmpty.text = "VPN 未连接，连接后即可实时查看分流连接"
                tvEmpty.visibility = View.VISIBLE
                btnCloseAll.isEnabled = false
                adapter.submitList(emptyList())
            }
            return
        }

        val pm = context?.packageManager ?: return
        val service = currentService()
        val items = withContext(Dispatchers.IO) {
            val list = mutableListOf<ConnectionItem>()
            try {
                val rawNative = runCatching { service?.getActiveConnectionsJson() }.getOrNull().orEmpty()
                if (rawNative.isNotBlank()) {
                    val json = JSONObject(rawNative)
                    val connArr = json.optJSONArray("connections")
                    if (connArr != null) {
                        for (i in 0 until connArr.length()) {
                            val obj = connArr.optJSONObject(i) ?: continue
                            val id = obj.optString("id")
                            val network = obj.optString("network").ifBlank { "TCP" }
                            val host = obj.optString("host").ifBlank { "未知目标" }
                            val port = obj.optInt("port", 0)
                            val fullHost = if (port > 0) "$host:$port" else host

                            val rawPkg = obj.optString("packageName", "")
                            val uid = obj.optInt("uid", -1)
                            val (appName, pkg, icon) = resolveAppDisplay(pm, rawPkg, uid)

                            val rawRule = obj.optString("rule").trim()
                            val ruleDesc = if (rawRule.isNotBlank()) "规则: $rawRule" else "规则: 默认匹配"
                            val chainsStr = parseChainsArray(
                                obj.optJSONArray("chains"),
                                reverse = false,
                                fallback = obj.optString("outbound"),
                            )
                            val up = obj.optLong("upload", 0L)
                            val down = obj.optLong("download", 0L)

                            list.add(
                                ConnectionItem(
                                    id = id,
                                    appName = appName,
                                    packageName = pkg,
                                    icon = icon,
                                    network = network,
                                    host = fullHost,
                                    rule = ruleDesc,
                                    upload = up,
                                    download = down,
                                    chains = chainsStr,
                                )
                            )
                        }
                    }
                } else if (DataStore.enableClashAPI || DataStore.allowAccess) {
                    val req = buildClashRequest("http://127.0.0.1:9090/connections").build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val body = resp.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val connArr = json.optJSONArray("connections") ?: return@use

                        for (i in 0 until connArr.length()) {
                            val obj = connArr.optJSONObject(i) ?: continue
                            val id = obj.optString("id")
                            val meta = obj.optJSONObject("metadata")
                            val network = meta?.optString("network")?.uppercase()?.ifBlank { "TCP" } ?: "TCP"
                            val host = meta?.optString("host")?.takeIf { it.isNotBlank() }
                                ?: meta?.optString("destinationIP")?.takeIf { it.isNotBlank() }
                                ?: "未知目标"
                            val port = meta?.optString("destinationPort")?.takeIf { it.isNotBlank() && it != "0" } ?: ""
                            val fullHost = if (port.isNotBlank()) "$host:$port" else host

                            val rawPkg = stripProcessSuffix(
                                meta?.optString("processPath")?.takeIf { it.isNotBlank() }
                                    ?: meta?.optString("process").orEmpty()
                            )
                            val (appName, pkg, icon) = resolveAppDisplay(pm, rawPkg, -1)

                            val rule = obj.optString("rule")
                            val payload = obj.optString("rulePayload")
                            val ruleDesc = when {
                                payload.isNotBlank() -> "规则: $rule($payload)"
                                rule.isNotBlank() -> "规则: $rule"
                                else -> "规则: 默认匹配"
                            }

                            val up = obj.optLong("upload", 0L)
                            val down = obj.optLong("download", 0L)
                            val chainsStr = parseChainsArray(
                                obj.optJSONArray("chains"),
                                reverse = true,
                                fallback = "直连",
                            )

                            list.add(
                                ConnectionItem(
                                    id = id,
                                    appName = appName,
                                    packageName = pkg,
                                    icon = icon,
                                    network = network,
                                    host = fullHost,
                                    rule = ruleDesc,
                                    upload = up,
                                    download = down,
                                    chains = chainsStr,
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
            list
        }

        withContext(Dispatchers.Main) {
            if (!isAdded || view == null) return@withContext
            allConnections = items
            tvActiveCount.text = "活跃连接: ${items.size}"
            btnCloseAll.isEnabled = items.isNotEmpty()
            tvEmpty.text = getString(R.string.connections_empty)
            applyFilter()
        }
    }

    private fun applyFilter() {
        val filtered = if (filterKeyword.isEmpty()) {
            allConnections
        } else {
            allConnections.filter {
                it.host.contains(filterKeyword, ignoreCase = true) ||
                    it.appName.contains(filterKeyword, ignoreCase = true) ||
                    it.packageName.contains(filterKeyword, ignoreCase = true) ||
                    it.rule.contains(filterKeyword, ignoreCase = true) ||
                    it.chains.contains(filterKeyword, ignoreCase = true)
            }
        }
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(filtered)
    }

    private inner class ConnectionsAdapter : RecyclerView.Adapter<ConnectionViewHolder>() {
        private var list = listOf<ConnectionItem>()

        fun submitList(newList: List<ConnectionItem>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConnectionViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_connection, parent, false)
            return ConnectionViewHolder(v)
        }

        override fun onBindViewHolder(holder: ConnectionViewHolder, position: Int) {
            holder.bind(list[position])
        }

        override fun getItemCount(): Int = list.size
    }

    private inner class ConnectionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.conn_app_icon)
        val appName: TextView = itemView.findViewById(R.id.conn_app_name)
        val network: TextView = itemView.findViewById(R.id.conn_network)
        val host: TextView = itemView.findViewById(R.id.conn_host)
        val rule: TextView = itemView.findViewById(R.id.conn_rule)
        val traffic: TextView = itemView.findViewById(R.id.conn_traffic)
        val chains: TextView = itemView.findViewById(R.id.conn_chains)
        val closeBtn: ImageView = itemView.findViewById(R.id.conn_close_btn)

        fun bind(item: ConnectionItem) {
            appName.text = item.appName
            network.text = item.network
            host.text = item.host
            rule.text = item.rule
            chains.text = "链路: ${item.chains}"

            val ctx = itemView.context
            val upStr = Formatter.formatFileSize(ctx, item.upload)
            val downStr = Formatter.formatFileSize(ctx, item.download)
            traffic.text = "▲ $upStr  ▼ $downStr"

            if (item.icon != null) {
                appIcon.setImageDrawable(item.icon)
            } else {
                appIcon.setImageResource(R.drawable.ic_navigation_apps)
            }

            closeBtn.setOnClickListener {
                val service = currentService()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    if (service != null) {
                        runCatching { service.closeActiveConnection(item.id) }
                    } else if (DataStore.enableClashAPI || DataStore.allowAccess) {
                        runCatching {
                            val req = buildClashRequest("http://127.0.0.1:9090/connections/${item.id}")
                                .delete()
                                .build()
                            httpClient.newCall(req).execute().close()
                        }
                    }
                    fetchConnections()
                }
            }
        }
    }
}
