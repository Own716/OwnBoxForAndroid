package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.SparseBooleanArray
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.core.util.contains
import androidx.core.util.set
import androidx.core.view.ViewCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutAppsBinding
import io.nekohasekai.sagernet.databinding.LayoutAppsItemBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.crossFadeFrom
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.route.AppClassifier
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class AppManagerActivity : ThemedActivity() {
    companion object {
        const val EXTRA_SEARCH_QUERY = "search_query"

        @SuppressLint("StaticFieldLeak")
        private var instance: AppManagerActivity? = null
        private const val SWITCH = "switch"

        private val cachedApps
            get() = PackageCache.installedPackages.toMutableMap().apply {
                remove(BuildConfig.APPLICATION_ID)
            }
    }

    private class ProxiedApp(
        private val pm: PackageManager,
        private val appInfo: ApplicationInfo,
        val packageName: String,
    ) {
        val name: CharSequence = PackageCache.loadLabel(appInfo, pm)    // cached for sorting & safe against OOM
        val icon: Drawable get() = PackageCache.loadIcon(appInfo, pm)
        val uid get() = appInfo.uid
        val sys get() = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    private inner class AppViewHolder(val binding: LayoutAppsItemBinding) : RecyclerView.ViewHolder(
        binding.root
    ),
        View.OnClickListener {
        private lateinit var item: ProxiedApp

        init {
            binding.root.setOnClickListener(this)
        }

        fun bind(app: ProxiedApp) {
            item = app
            try {
                binding.itemicon.setImageDrawable(app.icon)
            } catch (t: Throwable) {
                Logs.e(t)
            }
            binding.title.text = app.name
            binding.desc.text = "${app.packageName} (${app.uid})"
            binding.itemcheck.isChecked = isProxiedApp(app)
        }

        fun handlePayload(payloads: List<Any>) {
            if (payloads.any { it == SWITCH }) binding.itemcheck.isChecked = isProxiedApp(item)
        }

        override fun onClick(v: View?) {
            hasConfigChanged = true
            DataStore.dirty = true
            val targetUid = item.uid
            if (isProxiedApp(item)) proxiedUids.delete(targetUid) else proxiedUids[targetUid] = true
            val currentList = appsAdapter.filteredApps
            var notifiedAny = false
            for (idx in currentList.indices) {
                if (currentList[idx].uid == targetUid) {
                    appsAdapter.notifyItemChanged(idx, SWITCH)
                    notifiedAny = true
                }
            }
            if (!notifiedAny) {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    appsAdapter.notifyItemChanged(pos, SWITCH)
                }
            }
            scheduleSaveIndividual()
        }
    }

    private fun buildIndividualSnapshot(): String {
        return apps.filter { isProxiedApp(it) }.joinToString("\n") { it.packageName }
    }

    private var saveIndividualJob: Job? = null
    private fun scheduleSaveIndividual(immediate: Boolean = false) {
        saveIndividualJob?.cancel()
        if (apps.isEmpty()) return
        if (immediate) {
            DataStore.individual = buildIndividualSnapshot()
            return
        }
        saveIndividualJob = lifecycleScope.launch {
            delay(300)
            if (apps.isEmpty()) return@launch
            val individualStr = buildIndividualSnapshot()
            withContext(Dispatchers.IO) {
                DataStore.individual = individualStr
            }
        }
    }

    private inner class AppsAdapter : RecyclerView.Adapter<AppViewHolder>(),
        Filterable,
        FastScrollRecyclerView.SectionedAdapter {
        var filteredApps = apps

        suspend fun reload(proxiedSnapshot: SparseBooleanArray): List<ProxiedApp> {
            return cachedApps.mapNotNull { (packageName, packageInfo) ->
                coroutineContext[Job]!!.ensureActive()
                val appInfo = packageInfo.applicationInfo ?: return@mapNotNull null
                try {
                    ProxiedApp(packageManager, appInfo, packageName)
                } catch (t: Throwable) {
                    Logs.e(t)
                    null
                }
            }.sortedWith(compareBy({ !proxiedSnapshot[it.uid] }, { it.name.toString() }))
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) =
            holder.bind(filteredApps[position])

        override fun onBindViewHolder(holder: AppViewHolder, position: Int, payloads: List<Any>) {
            if (payloads.isNotEmpty()) {
                holder.handlePayload(payloads)
                return
            }

            onBindViewHolder(holder, position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder =
            AppViewHolder(LayoutAppsItemBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = filteredApps.size

        private val filterImpl = object : Filter() {
            override fun performFiltering(constraint: CharSequence) = FilterResults().apply {
                var filteredApps = if (constraint.isEmpty()) apps else apps.filter {
                    it.name.contains(constraint, true) || it.packageName.contains(
                        constraint, true
                    ) || it.uid.toString().contains(constraint)
                }
                if (!sysApps) filteredApps = filteredApps.filter { !it.sys }
                count = filteredApps.size
                values = filteredApps
            }

            override fun publishResults(constraint: CharSequence, results: FilterResults) {
                @Suppress("UNCHECKED_CAST")
                filteredApps = results.values as List<ProxiedApp>
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter = filterImpl

        override fun getSectionName(position: Int): String {
            return filteredApps[position].name.firstOrNull()?.toString() ?: ""
        }

    }

    private val loading by lazy { findViewById<View>(R.id.loading) }

    private lateinit var binding: LayoutAppsBinding
    private val proxiedUids = SparseBooleanArray()
    private var loader: Job? = null
    private var apps = emptyList<ProxiedApp>()
    private val appsAdapter = AppsAdapter()
    private var initialProxyApps = false
    private var initialBypass = false
    private var initialIndividual = ""
    private var hasConfigChanged = false

    private fun initProxiedUids(str: String = DataStore.individual) {
        proxiedUids.clear()
        val apps = cachedApps
        for (line in str.lineSequence()) {
            val app = (apps[line] ?: continue)
            val uid = app.applicationInfo?.uid ?: continue
            proxiedUids[uid] = true
        }
    }

    private fun isProxiedApp(app: ProxiedApp) = proxiedUids[app.uid]

    @UiThread
    private fun loadApps() {
        loader?.cancel()
        loader = lifecycleScope.launch {
            binding.appPlaceholder.root.visibility = View.GONE
            loading.crossFadeFrom(binding.list)
            val adapter = binding.list.adapter as AppsAdapter
            withContext(Dispatchers.IO) {
                try {
                    PackageCache.reload()
                } catch (t: Throwable) {
                    Logs.e(t)
                }
            }
            if (!hasConfigChanged) {
                initProxiedUids()
            }
            val proxiedSnapshot = proxiedUids.clone()
            apps = withContext(Dispatchers.IO) { adapter.reload(proxiedSnapshot) }
            adapter.filter.filter(binding.search.text?.toString() ?: "")
            if (apps.isEmpty()) {
                binding.list.visibility = View.GONE
                binding.appPlaceholder.root.crossFadeFrom(loading)
            } else {
                binding.appPlaceholder.root.visibility = View.GONE
                binding.list.crossFadeFrom(loading)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appPlaceholder.openSettings.setOnClickListener {
            val intent =
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
            startActivity(intent)
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setTitle(R.string.proxied_apps)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        initialProxyApps = DataStore.proxyApps
        initialBypass = DataStore.bypass
        initialIndividual = DataStore.individual

        if (!DataStore.proxyApps) {
            DataStore.proxyApps = true
        }

        binding.bypassGroup.check(if (DataStore.bypass) R.id.appProxyModeBypass else R.id.appProxyModeOn)
        updateAutoSelectButtonText()
        binding.bypassGroup.setOnCheckedChangeListener { _, checkedId ->
            hasConfigChanged = true
            DataStore.dirty = true
            when (checkedId) {
                R.id.appProxyModeDisable -> {
                    DataStore.proxyApps = false
                    finish()
                }

                R.id.appProxyModeOn -> {
                    DataStore.bypass = false
                    updateAutoSelectButtonText()
                }

                R.id.appProxyModeBypass -> {
                    DataStore.bypass = true
                    updateAutoSelectButtonText()
                }
            }
        }
        binding.autoSelectProxyApps.setOnClickListener { selectProxyApp() }

        initProxiedUids()
        binding.list.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.list.itemAnimator = DefaultItemAnimator()
        binding.list.adapter = appsAdapter

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)

        binding.search.addTextChangedListener {
            appsAdapter.filter.filter(it?.toString() ?: "")
        }

        binding.showSystemApps.isChecked = sysApps
        binding.showSystemApps.setOnCheckedChangeListener { _, isChecked ->
            sysApps = isChecked
            appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
        }

        intent?.getStringExtra(EXTRA_SEARCH_QUERY)?.takeIf { it.isNotBlank() }?.let { initialQuery ->
            sysApps = true
            binding.showSystemApps.isChecked = true
            binding.search.setText(initialQuery)
        }

        instance = this
        loadApps()
    }

    private var sysApps = true

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.per_app_proxy_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_invert_selections -> {
                runOnDefaultDispatcher {
                    hasConfigChanged = true
                    DataStore.dirty = true
                    val proxiedUidsOld = proxiedUids.clone()
                    for (app in apps) {
                        if (proxiedUidsOld.contains(app.uid)) {
                            proxiedUids.delete(app.uid)
                        } else {
                            proxiedUids[app.uid] = true
                        }
                    }
                    DataStore.individual = apps.filter { isProxiedApp(it) }
                        .joinToString("\n") { it.packageName }
                    apps = apps.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))
                    onMainDispatcher {
                        appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
                    }
                }

                return true
            }

            R.id.action_clear_selections -> {
                runOnDefaultDispatcher {
                    hasConfigChanged = true
                    DataStore.dirty = true
                    proxiedUids.clear()
                    DataStore.individual = ""
                    apps = apps.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))
                    onMainDispatcher {
                        appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
                    }
                }
            }

            R.id.action_export_clipboard -> {
                val success =
                    SagerNet.trySetPrimaryClip("${DataStore.bypass}\n${DataStore.individual}")
                Snackbar.make(
                    binding.list,
                    if (success) R.string.action_export_msg else R.string.action_export_err,
                    Snackbar.LENGTH_LONG
                ).show()
                return true
            }

            R.id.action_import_clipboard -> {
                val proxiedAppString =
                    SagerNet.clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!proxiedAppString.isNullOrEmpty()) {
                    val i = proxiedAppString.indexOf('\n')
                    try {
                        val (enabled, apps) = if (i < 0) {
                            proxiedAppString to ""
                        } else proxiedAppString.substring(
                            0, i
                        ) to proxiedAppString.substring(i + 1)
                        binding.bypassGroup.check(if (enabled.toBoolean()) R.id.appProxyModeBypass else R.id.appProxyModeOn)
                        updateAutoSelectButtonText()
                        DataStore.individual = apps
                        hasConfigChanged = true
                        DataStore.dirty = true
                        Snackbar.make(
                            binding.list, R.string.action_import_msg, Snackbar.LENGTH_LONG
                        ).show()
                        initProxiedUids(apps)
                        appsAdapter.notifyItemRangeChanged(0, appsAdapter.itemCount, SWITCH)
                        return true
                    } catch (_: IllegalArgumentException) {
                    }
                }
                Snackbar.make(binding.list, R.string.action_import_err, Snackbar.LENGTH_LONG).show()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun updateAutoSelectButtonText() {
        binding.autoSelectProxyApps.setText(
            if (DataStore.bypass) R.string.auto_select_bypass_apps else R.string.auto_select_proxy_apps
        )
    }

    private fun selectProxyApp() {
        if (!DataStore.bypass) {
            showAutoSelectDialog(
                titleRes = R.string.auto_select_proxy_apps,
                items = arrayOf(
                    getString(R.string.auto_select_opt_intl_browsers),
                    getString(R.string.auto_select_opt_google_core),
                    getString(R.string.auto_select_opt_play_heuristic),
                ),
                checked = booleanArrayOf(true, true, false),
            ) { replace, checked ->
                runAutoSelect(
                    bypass = false,
                    replace = replace,
                    proxyOptions = AppClassifier.ProxyAutoSelectOptions(
                        includeInternationalBrowsers = checked[0],
                        includeGoogleCoreServices = checked[1],
                        includePlayStoreHeuristic = checked[2],
                    ),
                )
            }
        } else {
            showAutoSelectDialog(
                titleRes = R.string.auto_select_bypass_apps,
                items = arrayOf(
                    getString(R.string.auto_select_opt_domestic_apps),
                    getString(R.string.auto_select_opt_oem_system),
                    getString(R.string.auto_select_opt_domestic_browsers),
                ),
                checked = booleanArrayOf(true, true, false),
            ) { replace, checked ->
                runAutoSelect(
                    bypass = true,
                    replace = replace,
                    bypassOptions = AppClassifier.BypassAutoSelectOptions(
                        includeDomesticApps = checked[0],
                        includeChineseOemSystemApps = checked[1],
                        includeDomesticBrowsers = checked[2],
                    ),
                )
            }
        }
    }

    private fun showAutoSelectDialog(
        titleRes: Int,
        items: Array<String>,
        checked: BooleanArray,
        onConfirm: (replace: Boolean, checked: BooleanArray) -> Unit,
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.auto_select_action_replace) { _, _ ->
                onConfirm(true, checked)
            }
            .setNeutralButton(R.string.auto_select_action_append) { _, _ ->
                onConfirm(false, checked)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runAutoSelect(
        bypass: Boolean,
        replace: Boolean,
        proxyOptions: AppClassifier.ProxyAutoSelectOptions? = null,
        bypassOptions: AppClassifier.BypassAutoSelectOptions? = null,
    ) {
        lifecycleScope.launch {
            try {
                val installed = cachedApps
                val matchedUids = withContext(Dispatchers.Default) {
                    AppClassifier.collectMatchingUids(applicationContext, installed) { signals, bundledForeign ->
                        if (!bypass) {
                            AppClassifier.shouldSelectForProxy(
                                signals = signals,
                                bundledForeign = bundledForeign,
                                options = proxyOptions ?: AppClassifier.ProxyAutoSelectOptions(),
                            )
                        } else {
                            AppClassifier.shouldSelectForBypass(
                                signals = signals,
                                bundledForeign = bundledForeign,
                                options = bypassOptions ?: AppClassifier.BypassAutoSelectOptions(),
                            )
                        }
                    }
                }

                hasConfigChanged = true
                DataStore.dirty = true
                saveIndividualJob?.cancel()

                val beforeCount = if (apps.isNotEmpty()) apps.count { isProxiedApp(it) } else proxiedUids.size()
                if (replace) {
                    proxiedUids.clear()
                }
                for (uid in matchedUids) {
                    proxiedUids[uid] = true
                }
                val afterCount = if (apps.isNotEmpty()) apps.count { isProxiedApp(it) } else proxiedUids.size()
                val addedCount = (afterCount - beforeCount).coerceAtLeast(0)

                val individualStr = if (apps.isNotEmpty()) {
                    buildIndividualSnapshot()
                } else {
                    installed.values.mapNotNull { info ->
                        val u = info.applicationInfo?.uid ?: return@mapNotNull null
                        if (proxiedUids[u]) info.packageName else null
                    }.joinToString("\n")
                }
                withContext(Dispatchers.IO) {
                    DataStore.individual = individualStr
                }

                apps = apps.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))
                appsAdapter.filter.filter(binding.search.text?.toString() ?: "")

                val message = when {
                    !bypass && replace -> getString(R.string.auto_select_proxy_result_replace, afterCount)
                    !bypass -> getString(R.string.auto_select_proxy_result_append, addedCount, afterCount)
                    replace -> getString(R.string.auto_select_bypass_result_replace, afterCount)
                    else -> getString(R.string.auto_select_bypass_result_append, addedCount, afterCount)
                }
                Snackbar.make(binding.list, message, Snackbar.LENGTH_LONG).show()
            } catch (e: Exception) {
                Logs.e(e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (apps.isEmpty() && loader?.isActive != true) {
            loadApps()
        }
    }

    override fun onPause() {
        super.onPause()
        if (apps.isNotEmpty() && saveIndividualJob?.isActive == true) {
            scheduleSaveIndividual(immediate = true)
        }
        checkAndApplyChanges()
    }

    private fun checkAndApplyChanges() {
        if (hasConfigChanged || initialProxyApps != DataStore.proxyApps || initialBypass != DataStore.bypass || initialIndividual != DataStore.individual) {
            hasConfigChanged = false
            initialProxyApps = DataStore.proxyApps
            initialBypass = DataStore.bypass
            initialIndividual = DataStore.individual
            DataStore.dirty = true
            if (DataStore.serviceState.started) {
                runOnDefaultDispatcher {
                    try {
                        SagerNet.restartService()
                        onMainDispatcher {
                            Toast.makeText(
                                this@AppManagerActivity,
                                R.string.app_proxy_updated_restart,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
            }
        }
    }

    override fun supportNavigateUpTo(upIntent: Intent) =
        super.supportNavigateUpTo(upIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))

    override fun onKeyUp(keyCode: Int, event: KeyEvent?) = if (keyCode == KeyEvent.KEYCODE_MENU) {
        if (binding.toolbar.isOverflowMenuShowing) binding.toolbar.hideOverflowMenu() else binding.toolbar.showOverflowMenu()
    } else super.onKeyUp(keyCode, event)

    override fun onDestroy() {
        instance = null
        loader?.cancel()
        super.onDestroy()
    }
}
