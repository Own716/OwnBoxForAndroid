package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutWebviewBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ui.dashboard.DashboardItem
import io.nekohasekai.sagernet.ui.dashboard.DashboardManager
import kotlinx.coroutines.launch
import moe.matsuri.nb4a.utils.WebViewUtil
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

// Fragment必须有一个无参public的构造函数，否则在数据恢复的时候，会报crash

class WebviewFragment : ToolbarFragment(R.layout.layout_webview), Toolbar.OnMenuItemClickListener {

    private val dashboardClient = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    lateinit var mWebView: WebView

    companion object {
        const val DEFAULT_YACD_URL = "http://127.0.0.1:9090/ui"
        const val PRESET_ZASHBOARD_URL = "https://board.zash.run.place/"
    }

    private fun updateToolbarSubtitle() {
        val active = DashboardManager.getActiveDashboard()
        toolbar.subtitle = active.name
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 规范化旧版本遗留的 setup 路由，使其直接访问根路径
        if (DataStore.yacdURL.startsWith("https://board.zash.run.place/#/setup")) {
            DataStore.yacdURL = DashboardManager.PRESET_ZASHBOARD_URL
        }

        // layout
        toolbar.setTitle(R.string.menu_dashboard)
        updateToolbarSubtitle()
        toolbar.inflateMenu(R.menu.yacd_menu)
        toolbar.setOnMenuItemClickListener(this)

        val binding = LayoutWebviewBinding.bind(view)

        // webview
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        mWebView = binding.webview
        mWebView.settings.apply {
            domStorageEnabled = true
            javaScriptEnabled = true
            allowFileAccess = true
            // 允许 HTTPS 外部面板（如 https://board.zash.run.place/）安全请求本地 HTTP Clash API（http://127.0.0.1:9090）
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        mWebView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString()?.toHttpUrlOrNull() ?: return null
                if (!request.isForMainFrame || request.method != "GET" ||
                    !LocalYacdDashboard.isLocalDocument(url.toString())) return null
                val readiness = LocalYacdDashboard.readiness(DataStore.serviceState.connected,
                    DataStore.enableClashAPI || DataStore.allowAccess)
                if (readiness != null) return dashboardError(readiness)
                val secret = DataStore.clashApiSecret
                val apiFailure = LocalYacdDashboard.checkApi(dashboardClient, secret)
                if (apiFailure != null) return dashboardError(apiFailure)
                return try {
                    val documentUrl = if (url.encodedPath == "/ui")
                        url.newBuilder().encodedPath("/ui/").build() else url
                    dashboardClient.newCall(Request.Builder().url(documentUrl).build()).execute().use { response ->
                        if (!response.isSuccessful) return dashboardError(LocalYacdDashboard.Failure.HTML)
                        val html = response.body?.string().orEmpty()
                        if (!html.contains("<head>")) return dashboardError(LocalYacdDashboard.Failure.HTML)
                        val script = mWebView.context.assets.open("yacd-bootstrap.js")
                            .bufferedReader().use { it.readText() }
                        val bootstrap = LocalYacdDashboard.bootstrap(script, secret,
                            mWebView.context.getString(R.string.dashboard_storage_unavailable))
                        val document = html.replaceFirst("<head>", "<head><base href=\"/ui/\">$bootstrap")
                        WebResourceResponse("text/html", "UTF-8", 200, "OK",
                            mapOf("Cache-Control" to "no-store"), document.byteInputStream())
                    }
                } catch (_: Exception) {
                    dashboardError(LocalYacdDashboard.Failure.HTML)
                }
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                WebViewUtil.onReceivedError(view, request, error)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null) {
                    if (url.contains("zash") || url.contains("run.place") || url.contains("board")) {
                        injectZashboardAutoConnect(view)
                    } else if (url.contains("metacubex") || url.contains("metacubexd")) {
                        injectMetaCubeXDAutoConnect(view)
                    }
                }
            }
        }
        mWebView.webChromeClient = WebChromeClient()

        loadDashboard(DataStore.yacdURL)

        if (!DataStore.serviceState.connected && LocalYacdDashboard.isLocalDocument(DataStore.yacdURL)) {
            Snackbar.make(view, R.string.dashboard_connect_service, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun dashboardError(failure: LocalYacdDashboard.Failure): WebResourceResponse {
        val message = when (failure) {
            LocalYacdDashboard.Failure.DISCONNECTED -> R.string.dashboard_connect_service
            LocalYacdDashboard.Failure.DISABLED -> R.string.dashboard_enable_api
            LocalYacdDashboard.Failure.AUTHENTICATION -> R.string.dashboard_auth_failed
            LocalYacdDashboard.Failure.API -> R.string.dashboard_api_unavailable
            LocalYacdDashboard.Failure.HTML -> R.string.dashboard_html_unavailable
        }
        val context = mWebView.context
        val html = LocalYacdDashboard.errorHtml(context.getString(message), context.getString(R.string.action_refresh))
        return WebResourceResponse("text/html", "UTF-8", 503, "Service Unavailable",
            mapOf("Cache-Control" to "no-store"), html.byteInputStream())
    }

    private fun injectZashboardAutoConnect(view: WebView?) {
        val secret = DataStore.clashApiSecret
        val js = """
            (function() {
                var secret = "$secret";
                var listKey = "setup/api-list";
                var activeKey = "setup/active-uuid";

                // 1. 同步注入 / 更新 localStorage 的后端列表与密钥凭证
                try {
                    var list = JSON.parse(localStorage.getItem(listKey) || "[]");
                    if (!Array.isArray(list)) list = [];
                    var targetUuid = "ownbox-local";
                    var found = false;
                    for (var i = 0; i < list.length; i++) {
                        var item = list[i];
                        if (item && (item.host === "127.0.0.1" || item.host === "localhost") && String(item.port) === "9090") {
                            item.password = secret;
                            item.protocol = "http";
                            item.type = "clash";
                            targetUuid = item.uuid || targetUuid;
                            item.uuid = targetUuid;
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        list.unshift({
                            type: "clash",
                            protocol: "http",
                            host: "127.0.0.1",
                            port: "9090",
                            secondaryPath: "",
                            password: secret,
                            uuid: targetUuid,
                            label: "OwnBox 本地内核",
                            disableUpgradeCore: true,
                            disableTunMode: false
                        });
                    }
                    localStorage.setItem(listKey, JSON.stringify(list));
                    localStorage.setItem(activeKey, targetUuid);
                    try { window.dispatchEvent(new Event('storage')); } catch (_) {}
                } catch (_) {}

                // 2. 轮询侦听并自愈 DOM 状态（处理修改后端配置弹窗、连接失败弹窗与 setup 提交）
                var checkCount = 0;
                var maxChecks = 40;
                var checkInterval = setInterval(function() {
                    checkCount++;
                    if (checkCount > maxChecks) {
                        clearInterval(checkInterval);
                        return;
                    }

                    // 自动填充任何密码输入框（图二“修改后端配置”弹窗）
                    var pwInputs = document.querySelectorAll('input[type="password"], input[placeholder*="密码"], input[placeholder*="password"], input[placeholder*="Secret"]');
                    var filled = false;
                    pwInputs.forEach(function(inp) {
                        if (inp && inp.value !== secret) {
                            inp.value = secret;
                            try {
                                inp.dispatchEvent(new Event('input', { bubbles: true }));
                                inp.dispatchEvent(new Event('change', { bubbles: true }));
                            } catch (_) {}
                            filled = true;
                        }
                    });

                    // 静默清理“未授权，请重新登录”、“密码不对”等过渡报错气泡
                    var alerts = document.querySelectorAll('.el-notification, .el-message, [role="alert"], div[class*="toast"], div[class*="alert"]');
                    alerts.forEach(function(el) {
                        var text = el.innerText || '';
                        if (text.indexOf('后端连不上') !== -1 || text.indexOf('未授权') !== -1 || text.indexOf('密码不对') !== -1) {
                            el.style.display = 'none';
                        }
                    });

                    var buttons = Array.from(document.querySelectorAll('button, a'));

                    // 如果检测到“连接失败 / Unauthorized”弹窗（图三），自动触发“重试”
                    var retryBtn = buttons.find(function(b) {
                        var t = b.textContent ? b.textContent.trim() : '';
                        return t === '重试' || t === 'Retry';
                    });
                    if (retryBtn && !retryBtn.disabled) {
                        retryBtn.click();
                    }

                    // 如果在 setup 或修改后端配置弹窗，且密码已填充或显示连接正常，自动点击“提交”
                    var submitBtn = buttons.find(function(b) {
                        var t = b.textContent ? b.textContent.trim() : '';
                        return t === '提交' || t === 'Submit' || t === '保存' || t === 'Save';
                    });
                    if (submitBtn && !submitBtn.disabled) {
                        submitBtn.click();
                        // 若位于 #setup 路由，提交后尝试切换到根路径
                        if (window.location.hash.indexOf('setup') !== -1) {
                            setTimeout(function() {
                                if (window.location.hash.indexOf('setup') !== -1) {
                                    window.location.hash = '#/';
                                }
                            }, 500);
                        }
                        clearInterval(checkInterval);
                        return;
                    }

                    // 若已处于主界面且无报错弹窗，终止轮询
                    if (window.location.hash.indexOf('setup') === -1 && !document.querySelector('.modal, [role="dialog"]')) {
                        clearInterval(checkInterval);
                    }
                }, 200);
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun injectMetaCubeXDAutoConnect(view: WebView?) {
        val secret = DataStore.clashApiSecret
        val js = """
            (function() {
                var secret = "$secret";
                var localUrl = "http://127.0.0.1:9090";

                // 1. 同步注入与自愈 Pinia / useLocalStorage 状态
                try {
                    var endpointList = JSON.parse(localStorage.getItem("endpointList") || "[]");
                    if (!Array.isArray(endpointList)) endpointList = [];
                    var found = false;
                    for (var i = 0; i < endpointList.length; i++) {
                        var ep = endpointList[i];
                        if (ep && (ep.url === localUrl || ep.url === "http://localhost:9090" || ep.url === "http://127.0.0.1:9090/")) {
                            ep.secret = secret;
                            ep.url = localUrl;
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        endpointList.unshift({
                            id: "ownbox-local",
                            url: localUrl,
                            secret: secret
                        });
                    }
                    localStorage.setItem("endpointList", JSON.stringify(endpointList));
                    localStorage.setItem("selectedEndpoint", JSON.stringify("ownbox-local"));
                    try { window.dispatchEvent(new Event('storage')); } catch (_) {}
                } catch (_) {}

                // 2. 轮询侦听并自愈 DOM 表单输入框（解决 vue 响应式状态与 setup 页面输入）
                function setNativeValue(element, value) {
                    var valueSetter = Object.getOwnPropertyDescriptor(element.__proto__, 'value') ||
                                      Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                    if (valueSetter && valueSetter.set) {
                        valueSetter.set.call(element, value);
                    } else {
                        element.value = value;
                    }
                    element.dispatchEvent(new Event('input', { bubbles: true }));
                    element.dispatchEvent(new Event('change', { bubbles: true }));
                }

                var checkCount = 0;
                var maxChecks = 35;
                var interval = setInterval(function() {
                    checkCount++;
                    if (checkCount > maxChecks) {
                        clearInterval(interval);
                        return;
                    }

                    // 查找密码/密钥输入框
                    var pwInputs = document.querySelectorAll('input[type="password"], input[placeholder*="secret" i], input[placeholder*="密钥"], input[placeholder*="Secret"]');
                    var needSubmit = false;
                    pwInputs.forEach(function(inp) {
                        if (inp && inp.value !== secret) {
                            setNativeValue(inp, secret);
                            needSubmit = true;
                        }
                    });

                    // 查找后端地址输入框
                    var urlInputs = document.querySelectorAll('input[placeholder*="http" i], input[placeholder*="后端"], input[placeholder*="Endpoint" i]');
                    urlInputs.forEach(function(inp) {
                        if (inp && (!inp.value || inp.value === '')) {
                            setNativeValue(inp, localUrl);
                            needSubmit = true;
                        }
                    });

                    // 清理过渡报错气泡提示
                    var alerts = document.querySelectorAll('.alert, [role="alert"], div[class*="error"]');
                    alerts.forEach(function(el) {
                        var text = el.innerText || '';
                        if (text.indexOf('secret 被拒绝') !== -1 || text.indexOf('401') !== -1) {
                            el.style.display = 'none';
                        }
                    });

                    // 如果密码已填充，寻找“连接”或“Connect”按钮自动提交
                    if (needSubmit || pwInputs.length > 0) {
                        var buttons = Array.from(document.querySelectorAll('button, a'));
                        var connectBtn = buttons.find(function(b) {
                            var text = (b.textContent || '').trim();
                            return text === '连接' || text === 'Connect' || text === '保存' || text === '确定';
                        });
                        if (connectBtn && !connectBtn.disabled) {
                            connectBtn.click();
                        }
                    }

                    // 如果已经成功进入仪表盘概览主页（离开 setup 路由），停止轮询
                    if (window.location.hash.indexOf('setup') === -1 && window.location.hash.length > 2) {
                        clearInterval(interval);
                    }
                }, 200);
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun buildEffectiveDashboardUrl(url: String): String {
        val secret = DataStore.clashApiSecret
        val normalized = runCatching { DashboardManager.normalizeUrl(url) }.getOrDefault(url)
        return when {
            normalized.startsWith("https://board.zash.run.place/#/setup") -> DashboardManager.PRESET_ZASHBOARD_URL
            (normalized.contains("metacubex") || normalized.contains("metacubexd")) && !normalized.contains("hostname=") -> {
                val clean = normalized.trimEnd('/')
                "$clean/#/setup?hostname=127.0.0.1&port=9090&secret=$secret&http=true"
            }
            else -> normalized
        }
    }

    private fun loadDashboard(url: String) {
        val targetUrl = buildEffectiveDashboardUrl(url)
        mWebView.loadUrl(targetUrl)
        updateToolbarSubtitle()
    }

    override fun onBackPressed(): Boolean {
        if (::mWebView.isInitialized && mWebView.canGoBack()) {
            mWebView.goBack()
            return true
        }
        return false
    }

    override fun onDestroyView() {
        if (::mWebView.isInitialized) {
            try {
                mWebView.stopLoading()
                mWebView.loadUrl("about:blank")
                mWebView.clearHistory()
                (mWebView.parent as? ViewGroup)?.removeView(mWebView)
                mWebView.destroy()
            } catch (e: Exception) {
                Logs.w("Failed to destroy WebView: ${e.message}")
            }
        }
        super.onDestroyView()
    }

    @SuppressLint("CheckResult")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_refresh -> {
                if (::mWebView.isInitialized) {
                    mWebView.reload()
                    view?.let { Snackbar.make(it, R.string.action_refresh, Snackbar.LENGTH_SHORT).show() }
                }
            }
            R.id.action_set_url -> {
                showDashboardPresetDialog()
            }
            R.id.close -> {
                (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_configuration)
            }
        }
        return true
    }

    private fun showDashboardPresetDialog() {
        val dialogContext = requireContext()
        val dialogView = LayoutInflater.from(dialogContext).inflate(R.layout.layout_dialog_dashboard_url, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.ll_dashboard_items)
        val btnAdd = dialogView.findViewById<MaterialButton>(R.id.btn_add_dashboard)
        val textApiSecret = dialogView.findViewById<TextView>(R.id.text_api_secret_masked)
        val btnCopySecret = dialogView.findViewById<MaterialButton>(R.id.btn_copy_secret)

        var dialog: androidx.appcompat.app.AlertDialog? = null

        val secret = DataStore.clashApiSecret
        if (secret.isNotBlank()) {
            val masked = if (secret.length > 14) "${secret.take(6)}...${secret.takeLast(6)}" else secret
            textApiSecret.text = getString(R.string.dashboard_api_secret_label, masked)
        } else {
            textApiSecret.text = getString(R.string.dashboard_api_secret_label, "(none)")
        }
        btnCopySecret.setOnClickListener {
            val cm = dialogContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Clash API Secret", secret))
            Toast.makeText(dialogContext, R.string.dashboard_secret_copied, Toast.LENGTH_SHORT).show()
        }

        fun renderList() {
            container.removeAllViews()
            val list = DashboardManager.getDashboards()
            val currentUrl = DataStore.yacdURL.trim()

            for (item in list) {
                val itemView = LayoutInflater.from(dialogContext).inflate(R.layout.item_dashboard, container, false)
                val textName = itemView.findViewById<TextView>(R.id.text_dashboard_name)
                val textUrl = itemView.findViewById<TextView>(R.id.text_dashboard_url)
                val badgePreset = itemView.findViewById<TextView>(R.id.badge_dashboard_preset)
                val badgeDefault = itemView.findViewById<TextView>(R.id.badge_dashboard_default)
                val textDiag = itemView.findViewById<TextView>(R.id.text_dashboard_diag)
                val btnTest = itemView.findViewById<MaterialButton>(R.id.btn_test_connection)
                val btnEdit = itemView.findViewById<MaterialButton>(R.id.btn_edit_dashboard)
                val btnDelete = itemView.findViewById<MaterialButton>(R.id.btn_delete_dashboard)
                val btnSelect = itemView.findViewById<MaterialButton>(R.id.btn_select_dashboard)

                textName.text = item.name
                textUrl.text = item.url
                badgePreset.isVisible = item.isPreset
                badgeDefault.isVisible = item.isDefault

                val isActive = DashboardManager.isUrlMatching(item.url, currentUrl)
                if (isActive) {
                    btnSelect.text = getString(R.string.dashboard_tab_active)
                    btnSelect.isEnabled = false
                } else {
                    btnSelect.text = getString(R.string.apply)
                    btnSelect.isEnabled = true
                }

                btnEdit.isVisible = !item.isPreset
                btnDelete.isVisible = !item.isPreset

                btnTest.setOnClickListener {
                    textDiag.isVisible = true
                    textDiag.text = getString(R.string.dashboard_testing)
                    textDiag.setTextColor(0xFF888888.toInt())
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = DashboardManager.testConnection(item.url, dashboardClient, dialogContext)
                        textDiag.text = buildString {
                            append(result.summary)
                            if (result.detail.isNotBlank()) {
                                append("\n").append(result.detail)
                            }
                        }
                        textDiag.setTextColor(if (result.success) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
                    }
                }

                btnEdit.setOnClickListener {
                    showEditDashboardDialog(item, dialog) {
                        renderList()
                    }
                }

                btnDelete.setOnClickListener {
                    MaterialAlertDialogBuilder(dialogContext)
                        .setTitle(R.string.delete)
                        .setMessage(getString(R.string.dashboard_delete_confirm))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            DashboardManager.deleteDashboard(item.id)
                            renderList()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }

                btnSelect.setOnClickListener {
                    DataStore.yacdURL = item.url
                    loadDashboard(item.url)
                    dialog?.dismiss()
                    this@WebviewFragment.view?.let { v ->
                        Snackbar.make(v, R.string.dashboard_switched_toast, Snackbar.LENGTH_SHORT).show()
                    }
                }

                container.addView(itemView)
            }
        }

        btnAdd.setOnClickListener {
            showEditDashboardDialog(null, dialog) {
                renderList()
            }
        }

        renderList()

        dialog = MaterialAlertDialogBuilder(dialogContext)
            .setTitle(R.string.dashboard_manage_title)
            .setView(dialogView)
            .setNeutralButton(R.string.dashboard_reset_default) { _, _ ->
                DashboardManager.setDefault(DashboardManager.PRESET_ZASHBOARD_ID)
                DataStore.yacdURL = DashboardManager.PRESET_ZASHBOARD_URL
                loadDashboard(DashboardManager.PRESET_ZASHBOARD_URL)
                this.view?.let { Snackbar.make(it, R.string.dashboard_switched_toast, Snackbar.LENGTH_SHORT).show() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEditDashboardDialog(
        editingItem: DashboardItem?,
        parentDialog: androidx.appcompat.app.AlertDialog?,
        onSaved: () -> Unit
    ) {
        val dialogContext = requireContext()
        val editView = LayoutInflater.from(dialogContext).inflate(R.layout.dialog_dashboard_edit, null)
        val editName = editView.findViewById<TextInputEditText>(R.id.edit_dashboard_name)
        val editUrl = editView.findViewById<TextInputEditText>(R.id.edit_dashboard_url)
        val btnTest = editView.findViewById<MaterialButton>(R.id.btn_edit_dialog_test)
        val progressTest = editView.findViewById<ProgressBar>(R.id.progress_edit_test)
        val textTestResult = editView.findViewById<TextView>(R.id.text_edit_test_result)
        val checkDefault = editView.findViewById<MaterialCheckBox>(R.id.checkbox_set_default)

        if (editingItem != null) {
            editName.setText(editingItem.name)
            editUrl.setText(editingItem.url)
            checkDefault.isChecked = editingItem.isDefault
        } else {
            checkDefault.isChecked = false
        }

        btnTest.setOnClickListener {
            val rawUrl = editUrl.text?.toString().orEmpty()
            if (rawUrl.isBlank()) {
                textTestResult.isVisible = true
                textTestResult.text = getString(R.string.dashboard_test_url_invalid)
                textTestResult.setTextColor(0xFFF44336.toInt())
                return@setOnClickListener
            }
            progressTest.isVisible = true
            btnTest.isEnabled = false
            textTestResult.isVisible = true
            textTestResult.text = getString(R.string.dashboard_testing)
            textTestResult.setTextColor(0xFF888888.toInt())

            viewLifecycleOwner.lifecycleScope.launch {
                val result = DashboardManager.testConnection(rawUrl, dashboardClient, dialogContext)
                progressTest.isVisible = false
                btnTest.isEnabled = true
                textTestResult.text = buildString {
                    append(result.summary)
                    if (result.detail.isNotBlank()) {
                        append("\n").append(result.detail)
                    }
                }
                textTestResult.setTextColor(if (result.success) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
            }
        }

        MaterialAlertDialogBuilder(dialogContext)
            .setTitle(if (editingItem != null) R.string.dashboard_edit_title else R.string.dashboard_add_title)
            .setView(editView)
            .setPositiveButton(R.string.dashboard_save_and_apply) { _, _ ->
                val name = editName.text?.toString().orEmpty()
                val url = editUrl.text?.toString().orEmpty()
                val isDefault = checkDefault.isChecked

                try {
                    val normalized = DashboardManager.normalizeUrl(url)
                    if (editingItem != null) {
                        DashboardManager.updateDashboard(editingItem.id, name, normalized, isDefault)
                    } else {
                        DashboardManager.addDashboard(name, normalized, isDefault)
                    }
                    DataStore.yacdURL = normalized
                    loadDashboard(normalized)
                    parentDialog?.dismiss()
                    onSaved()
                    this@WebviewFragment.view?.let { v ->
                        Snackbar.make(v, R.string.dashboard_switched_toast, Snackbar.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    this@WebviewFragment.view?.let { v ->
                        Snackbar.make(v, e.message ?: getString(R.string.dashboard_test_url_invalid), Snackbar.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
