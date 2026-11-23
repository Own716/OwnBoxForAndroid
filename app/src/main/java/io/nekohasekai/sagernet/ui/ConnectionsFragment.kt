package io.nekohasekai.sagernet.ui

import android.content.pm.PackageManager
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.PackageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ConnectionsFragment : ToolbarFragment(R.layout.layout_connections) {

    data class ConnectionItem(
        val id: String,
        val appName: String,
        val packageName: String,
        val network: String,
        val host: String,
        val rule: String,
        val upload: Long,
        val download: Long,
        val chains: String
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private lateinit var tvActiveCount: TextView
    private lateinit var btnCloseAll: MaterialButton
    private lateinit var etSearch: EditText
    private lateinit var recyclerConnections: RecyclerView
    private lateinit var tvEmpty: TextView

    private val adapter = ConnectionsAdapter()
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

        btnCloseAll.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder()
                        .url("http://127.0.0.1:9090/connections")
                        .delete()
                        .build()
                    httpClient.newCall(req).execute().close()
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

    private fun startPolling() {
        stopPolling()
        pollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                fetchConnections()
                delay(1500)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun fetchConnections() {
        if (!DataStore.serviceState.started) {
            withContext(Dispatchers.Main) {
                tvActiveCount.text = getString(R.string.connections_empty)
                tvEmpty.visibility = View.VISIBLE
                adapter.submitList(emptyList())
            }
            return
        }

        val items = withContext(Dispatchers.IO) {
            val list = mutableListOf<ConnectionItem>()
            try {
                val req = Request.Builder().url("http://127.0.0.1:9090/connections").build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    val json = JSONObject(body)
                    val connArr = json.optJSONArray("connections") ?: return@use

                    val pm = requireContext().packageManager
                    for (i in 0 until connArr.length()) {
                        val obj = connArr.getJSONObject(i)
                        val id = obj.optString("id")
                        val meta = obj.optJSONObject("metadata")
                        val network = meta?.optString("network")?.uppercase() ?: "TCP"
                        val host = meta?.optString("host")?.takeIf { it.isNotBlank() }
                            ?: meta?.optString("destinationIP") ?: "未知目标"
                        val port = meta?.optString("destinationPort")?.takeIf { it.isNotBlank() } ?: ""
                        val fullHost = if (port.isNotBlank()) "$host:$port" else host

                        val pkg = meta?.optString("process") ?: ""
                        val appName = if (pkg.isNotBlank()) {
                            try {
                                val info = pm.getApplicationInfo(pkg, 0)
                                pm.getApplicationLabel(info).toString()
                            } catch (_: Exception) {
                                pkg
                            }
                        } else {
                            "未知应用"
                        }

                        val rule = obj.optString("rule")
                        val payload = obj.optString("rulePayload")
                        val ruleDesc = when {
                            payload.isNotBlank() -> "规则: $rule($payload)"
                            rule.isNotBlank() -> "规则: $rule"
                            else -> "规则: 默认匹配"
                        }

                        val up = obj.optLong("upload", 0L)
                        val down = obj.optLong("download", 0L)

                        val chainsArr = obj.optJSONArray("chains")
                        val chainsStr = if (chainsArr != null && chainsArr.length() > 0) {
                            val chainList = mutableListOf<String>()
                            for (j in 0 until chainsArr.length()) {
                                chainList.add(chainsArr.getString(j))
                            }
                            chainList.joinToString(" » ")
                        } else {
                            "直连"
                        }

                        list.add(
                            ConnectionItem(
                                id = id,
                                appName = appName,
                                packageName = pkg,
                                network = network,
                                host = fullHost,
                                rule = ruleDesc,
                                upload = up,
                                download = down,
                                chains = chainsStr
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
            list
        }

        withContext(Dispatchers.Main) {
            allConnections = items
            tvActiveCount.text = "活跃连接: ${items.size}"
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

            if (item.packageName.isNotBlank()) {
                val icon = PackageCache[item.packageName]?.applicationInfo?.loadIcon(ctx.packageManager)
                if (icon != null) {
                    appIcon.setImageDrawable(icon)
                } else {
                    appIcon.setImageResource(R.drawable.ic_navigation_apps)
                }
            } else {
                appIcon.setImageResource(R.drawable.ic_navigation_apps)
            }

            closeBtn.setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        val req = Request.Builder()
                            .url("http://127.0.0.1:9090/connections/${item.id}")
                            .delete()
                            .build()
                        httpClient.newCall(req).execute().close()
                    }
                    fetchConnections()
                }
            }
        }
    }
}
