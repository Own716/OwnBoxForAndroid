package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.AppListPreference
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.OutboundPreference
import kotlinx.parcelize.Parcelize
import moe.matsuri.nb4a.ui.EditConfigPreference
import io.nekohasekai.sagernet.route.RouteRuleEditor
import io.nekohasekai.sagernet.ui.route.RouteRulePreferences
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("UNCHECKED_CAST")
class RouteSettingsActivity(
    @LayoutRes resId: Int = R.layout.layout_settings_activity,
) : ThemedActivity(resId),
    OnPreferenceDataStoreChangeListener {

    fun init(packageName: String?) {
        RuleEntity().apply {
            if (!packageName.isNullOrBlank()) {
                packages = setOf(packageName)
                name = app.getString(R.string.route_for, PackageCache.loadLabel(packageName))
            }
        }.init()
    }

    fun RuleEntity.init() {
        DataStore.routeName = name
        DataStore.serverConfig = config
        DataStore.routeDomain = domains
        DataStore.routeIP = ip
        DataStore.routePort = port
        DataStore.routeSourcePort = sourcePort
        DataStore.routeNetwork = network
        DataStore.routeSource = source
        DataStore.routeProtocol = protocol
        DataStore.routeRuleset = ruleset
        DataStore.routeOutboundRule = outbound
        DataStore.routeOutbound = when (outbound) {
            0L -> 0
            -1L -> 1
            -2L -> 2
            else -> OutboundPreference.VALUE_SELECT_PROFILE.toInt()
        }
        DataStore.routePackages = packages.joinToString("\n")
    }

    fun RuleEntity.serialize() {
        name = DataStore.routeName
        config = DataStore.serverConfig
        domains = DataStore.routeDomain
        ip = DataStore.routeIP
        port = DataStore.routePort
        sourcePort = DataStore.routeSourcePort
        network = DataStore.routeNetwork
        source = DataStore.routeSource
        protocol = DataStore.routeProtocol
        ruleset = DataStore.routeRuleset
        outbound = when (DataStore.routeOutbound) {
            0 -> 0L
            1 -> -1L
            2 -> -2L
            else -> DataStore.routeOutboundRule
        }
        packages = DataStore.routePackages.split("\n").filter { it.isNotBlank() }.toSet()

        if (DataStore.editingId == 0L) {
            enabled = true
        }
    }

    private lateinit var editConfigPreference: EditConfigPreference
    private var rulePreferences: RouteRulePreferences? = null
    private val saving = AtomicBoolean(false)

    fun needSave(): Boolean {
        return DataStore.dirty
    }

    fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.route_preferences)

        editConfigPreference = findPreference(Key.SERVER_CONFIG)!!
        rulePreferences = RouteRulePreferences(this).also { it.bind() }
    }

    override fun onResume() {
        super.onResume()

        if (::editConfigPreference.isInitialized) {
            editConfigPreference.notifyChanged()
            rulePreferences?.refresh()
        }
    }

    val selectProfileForAdd = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { (resultCode, data) ->
        if (resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                data!!.getLongExtra(
                    ProfileSelectActivity.EXTRA_PROFILE_ID, 0
                )
            ) ?: return@runOnDefaultDispatcher
            DataStore.routeOutboundRule = profile.id
            onMainDispatcher {
                outbound.value = OutboundPreference.VALUE_SELECT_PROFILE
            }
        }
    }

    val selectAppList = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { (_, _) ->
        apps.postUpdate()
    }

    val selectRuleSets = registerForActivityResult(
        io.nekohasekai.sagernet.ui.route.RuleSetPickerActivity.Contract()
    ) { list ->
        if (list != null) {
            val text = list.joinToString("\n")
            if (text != DataStore.routeRuleset) {
                DataStore.routeRuleset = text
                DataStore.dirty = true
            }
            rulePreferences?.refresh()
        }
    }

    lateinit var outbound: OutboundPreference
    lateinit var apps: AppListPreference

    fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        outbound = findPreference(Key.ROUTE_OUTBOUND)!!
        apps = findPreference(Key.ROUTE_PACKAGES)!!

        outbound.setOnPreferenceChangeListener { _, newValue ->
            if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                selectProfileForAdd.launch(
                    Intent(
                        this@RouteSettingsActivity, ProfileSelectActivity::class.java
                    ).apply {
                        ProfileManager.getProfile(DataStore.routeOutboundRule)?.let {
                            putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                        }
                    }
                )
                false
            } else {
                true
            }
        }

        apps.setOnPreferenceClickListener {
            selectAppList.launch(
                Intent(
                    this@RouteSettingsActivity, AppListActivity::class.java
                )
            )
            true
        }
    }

    fun displayPreferenceDialog(preference: Preference): Boolean {
        return false
    }

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    (requireActivity() as RouteSettingsActivity).saveAndExit()
                }
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    @Parcelize
    data class ProfileIdArg(val ruleId: Long) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<ProfileIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_route_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    ProfileManager.deleteRule(arg.ruleId)
                    if (DataStore.serviceState.started) {
                        runCatching { SagerNet.reloadService() }
                    }
                }
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    companion object {
        const val EXTRA_ROUTE_ID = "id"
        const val EXTRA_PACKAGE_NAME = "pkg"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.cag_route)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        if (savedInstanceState == null) {
            val editingId = intent.getLongExtra(EXTRA_ROUTE_ID, 0L)
            DataStore.editingId = editingId
            runOnDefaultDispatcher {
                if (editingId == 0L) {
                    init(intent.getStringExtra(EXTRA_PACKAGE_NAME))
                } else {
                    val ruleEntity = SagerDatabase.rulesDao.getById(editingId)
                    if (ruleEntity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        return@runOnDefaultDispatcher
                    }
                    ruleEntity.init()
                }

                onMainDispatcher {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.settings, MyPreferenceFragmentCompat())
                        .commit()

                    DataStore.dirty = false
                    DataStore.profileCacheStore.registerChangeListener(this@RouteSettingsActivity)
                }
            }


        } else {
            DataStore.profileCacheStore.registerChangeListener(this)
        }

    }

    suspend fun saveAndExit(allowCatchAll: Boolean = false) {
        if (!saving.compareAndSet(false, true)) return
        try {
            if (!needSave()) {
                onMainDispatcher {
                    MaterialAlertDialogBuilder(this@RouteSettingsActivity).setTitle(R.string.empty_route)
                        .setMessage(R.string.empty_route_notice)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                return
            }

            val editingId = DataStore.editingId
            val candidate = (if (editingId == 0L) RuleEntity() else SagerDatabase.rulesDao.getById(editingId)?.copy())
                ?: return
            candidate.serialize()
            if (candidate.packages.isNotEmpty() && DataStore.serviceMode != Key.MODE_VPN) {
                onMainDispatcher { MaterialAlertDialogBuilder(this@RouteSettingsActivity).setTitle(R.string.rr_invalid)
                    .setMessage(R.string.rr_apps_modes).setPositiveButton(android.R.string.ok, null).show() }
                return
            }
            val problems = RouteRuleEditor.problems(candidate)
            val errors = problems.filterNot { it.error == RouteRuleEditor.Error.EMPTY }
            if (errors.isNotEmpty()) {
                onMainDispatcher {
                    val messages = errors.joinToString("\n") { problem ->
                        val reason = when (problem.error) {
                            RouteRuleEditor.Error.JSON -> R.string.rr_json_error
                            RouteRuleEditor.Error.UNSUPPORTED -> R.string.rr_unsupported_error
                            RouteRuleEditor.Error.ACTION_OPTIONS -> R.string.rr_action_error
                            else -> R.string.rr_value_error
                        }
                        getString(R.string.rr_problem, problem.field, getString(reason))
                    }
                    MaterialAlertDialogBuilder(this@RouteSettingsActivity).setTitle(R.string.rr_invalid)
                        .setMessage(messages).setPositiveButton(android.R.string.ok, null).show()
                }
                return
            }
            if (!allowCatchAll && problems.any { it.error == RouteRuleEditor.Error.EMPTY }) {
                onMainDispatcher {
                    MaterialAlertDialogBuilder(this@RouteSettingsActivity).setTitle(R.string.rr_invalid)
                        .setMessage(R.string.rr_empty).setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.yes) { _, _ -> runOnDefaultDispatcher { saveAndExit(true) } }.show()
                }
                return
            }
            if (candidate.outbound > 0 && RouteRuleEditor.needsOutbound(candidate.config, candidate.outbound) &&
                ProfileManager.getProfile(candidate.outbound) == null) {
                onMainDispatcher { MaterialAlertDialogBuilder(this@RouteSettingsActivity).setTitle(R.string.rr_invalid)
                    .setMessage(R.string.rr_value_error).setPositiveButton(android.R.string.ok, null).show() }
                return
            }
            try {
                // Constructor + close only: actual pinned core schema/Go RE2 validation, no Start(),
                // TUN creation, DNS downloads or change to the running proxy instance.
                libcore.Libcore.newTestSingBoxInstance(RouteRuleEditor.validationConfig(candidate), null).close()
            } catch (e: Exception) {
                onMainDispatcher { MaterialAlertDialogBuilder(this@RouteSettingsActivity).setTitle(R.string.rr_invalid)
                    .setMessage(getString(R.string.rr_core_error, e.message.orEmpty()))
                    .setPositiveButton(android.R.string.ok, null).show() }
                return
            }
            if (editingId == 0L) {
                if (intent.hasExtra(EXTRA_PACKAGE_NAME)) {
                    setResult(RESULT_OK, Intent())
                }

                ProfileManager.createRule(candidate)
            } else {
                val entity = SagerDatabase.rulesDao.getById(DataStore.editingId)
                if (entity == null) {
                    finish()
                    return
                }
                ProfileManager.updateRule(candidate)
            }
            if (DataStore.serviceState.started) {
                runCatching { SagerNet.reloadService() }.onFailure {
                    onMainDispatcher { Toast.makeText(this@RouteSettingsActivity, R.string.rr_reload_error, Toast.LENGTH_LONG).show() }
                }
            }
            onMainDispatcher { finish() }
        } catch (e: Exception) {
            Logs.w(e)
            onMainDispatcher { MaterialAlertDialogBuilder(this@RouteSettingsActivity).setTitle(R.string.rr_invalid)
                .setMessage(R.string.rr_save_error).setPositiveButton(android.R.string.ok, null).show() }
        } finally { saving.set(false) }

    }

    val child by lazy { supportFragmentManager.findFragmentById(R.id.settings) as MyPreferenceFragmentCompat }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBackPressed(); return true }
        return (supportFragmentManager.findFragmentById(R.id.settings) as? MyPreferenceFragmentCompat)
            ?.onOptionsItemSelected(item) ?: super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (needSave()) {
            UnsavedChangesDialogFragment().apply { key() }.show(supportFragmentManager, null)
        } else super.onBackPressed()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
            runOnUiThread {
                rulePreferences?.refresh()
            }
        }
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        var activity: RouteSettingsActivity? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity = (requireActivity() as RouteSettingsActivity).apply {
                    createPreferences(savedInstanceState, rootKey)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    "Error on createPreferences, please try again.",
                    Toast.LENGTH_SHORT
                ).show()
                Logs.e(e)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)
            setDivider(null)
            setDividerHeight(0)

            activity?.apply {
                viewCreated(view, savedInstanceState)
            }
        }

        override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
            R.id.action_delete -> {
                if (DataStore.editingId == 0L) {
                    requireActivity().finish()
                } else {
                    DeleteConfirmationDialogFragment().apply {
                        arg(ProfileIdArg(DataStore.editingId))
                        key()
                    }.show(parentFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                runOnDefaultDispatcher {
                    activity?.saveAndExit()
                }
                true
            }

            else -> false
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            activity?.apply {
                if (displayPreferenceDialog(preference)) return
            }
            super.onDisplayPreferenceDialog(preference)
        }

    }

    object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "\u2022".repeat(text.length)
            }
        }

    }

}
