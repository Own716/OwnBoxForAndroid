package io.nekohasekai.sagernet.ui.route

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutRuleSetItemBinding
import io.nekohasekai.sagernet.databinding.LayoutRuleSetPickerBinding
import io.nekohasekai.sagernet.ktx.crossFadeFrom
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.route.RuleSetCatalog
import io.nekohasekai.sagernet.route.RuleSets
import io.nekohasekai.sagernet.ui.ThemedActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Searchable multi-select rule-set picker over `srslist.h` (~2300+ remote rule-sets)
 * plus custom `.srs` URLs, local imported rule-sets, and remote auto-update interval settings.
 */
class RuleSetPickerActivity : ThemedActivity() {

    companion object {
        const val EXTRA_RULE_SETS = "ruleSets"
        private const val STATE_SELECTED = "selected"
        private const val STATE_TAB = "currentTab"

        const val TAB_ALL = 0
        const val TAB_CUSTOM = 1
        const val TAB_SELECTED = 2
    }

    class Contract : ActivityResultContract<List<String>, List<String>?>() {
        override fun createIntent(context: Context, input: List<String>) =
            Intent(context, RuleSetPickerActivity::class.java).putStringArrayListExtra(EXTRA_RULE_SETS, ArrayList(input))

        override fun parseResult(resultCode: Int, intent: Intent?): List<String>? =
            if (resultCode == RESULT_OK) intent?.getStringArrayListExtra(EXTRA_RULE_SETS) else null
    }

    private enum class Kind { KNOWN, URL, LOCAL, CUSTOM_DB, UNKNOWN }

    private class Row(val entry: String, val source: String, val kind: Kind, val file: File? = null)

    private lateinit var binding: LayoutRuleSetPickerBinding
    private val selected = LinkedHashSet<String>()
    private var catalog = RuleSetCatalog(emptyList())
    private var rows = emptyList<Row>()
    private val adapter = RowAdapter()
    private var currentTab = TAB_ALL

    private val importRuleSetLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            } ?: "ruleset_${System.currentTimeMillis()}.srs"

            val rulesDir = File(getExternalFilesDir("rulesets") ?: filesDir, "rulesets").apply { mkdirs() }
            val destFile = File(rulesDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val customSet = DataStore.customRuleSets.toMutableSet()
            customSet.add(fileName)
            DataStore.customRuleSets = customSet

            val entryName = "local:$fileName"
            selected.add(entryName)
            updateSubtitle()

            currentTab = TAB_CUSTOM
            binding.ruleSetTabs.getTabAt(TAB_CUSTOM)?.select()
            rebuild()
            Toast.makeText(this, getString(R.string.rule_set_import_success, fileName), Toast.LENGTH_SHORT).show()
        }.onFailure {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.error_title)
                .setMessage(it.message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutRuleSetPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setTitle(R.string.rule_set_picker_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }

        val initial = savedInstanceState?.getStringArrayList(STATE_SELECTED)
            ?: intent.getStringArrayListExtra(EXTRA_RULE_SETS).orEmpty()
        initial.map { it.trim() }.filterTo(selected) { it.isNotEmpty() }
        updateSubtitle()

        binding.list.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.list.adapter = adapter
        ViewCompat.setOnApplyWindowInsetsListener(binding.list) { view, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = navBars.bottom + dp2px(16))
            insets
        }
        binding.search.addTextChangedListener { rebuild() }
        onBackPressedDispatcher.addCallback(this) { finishWithResult() }

        binding.ruleSetTabs.addTab(binding.ruleSetTabs.newTab().setText(R.string.rule_set_tab_all))
        binding.ruleSetTabs.addTab(binding.ruleSetTabs.newTab().setText(R.string.rule_set_tab_custom))
        binding.ruleSetTabs.addTab(binding.ruleSetTabs.newTab().setText(R.string.rule_set_tab_selected))

        binding.ruleSetTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: TAB_ALL
                rebuild()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        val restoredTab = savedInstanceState?.getInt(STATE_TAB, -1) ?: -1
        currentTab = when {
            restoredTab in TAB_ALL..TAB_SELECTED -> restoredTab
            selected.isNotEmpty() -> TAB_SELECTED
            else -> TAB_ALL
        }
        if (currentTab != TAB_ALL) {
            binding.ruleSetTabs.getTabAt(currentTab)?.select()
        }

        lifecycleScope.launch {
            catalog = withContext(Dispatchers.IO) { RuleSetCatalog.current() }
            binding.catalogEmpty.isVisible = catalog.size == 0 && currentTab == TAB_ALL
            rebuild()
            binding.list.crossFadeFrom(binding.loading)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SELECTED, ArrayList(selected))
        outState.putInt(STATE_TAB, currentTab)
    }

    private fun updateSubtitle() {
        val countText = getString(R.string.picker_selected, selected.size)
        val interval = RuleSets.normalizeUpdateInterval(DataStore.rulesUpdateInterval)
        supportActionBar?.subtitle = if (interval != null) {
            "$countText · ${RuleSets.formatUpdateInterval(this, interval)}"
        } else {
            countText
        }
    }

    private fun getCustomRows(): List<Row> {
        val result = mutableListOf<Row>()
        val seenEntries = mutableSetOf<String>()

        // 1. Local files in rulesets/ directory
        val rulesDir = File(getExternalFilesDir("rulesets") ?: filesDir, "rulesets")
        rulesDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                val entry = "local:${file.name}"
                seenEntries.add(entry)
                seenEntries.add(file.name)
                result.add(Row(entry, getString(R.string.rule_set_local_source), Kind.LOCAL, file))
            }
        }

        // 2. Custom db files in app storage (e.g. geoip-lite.db, geosite-lite.db)
        val extFilesDir = getExternalFilesDir(null) ?: filesDir
        extFilesDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".db") && file.name !in setOf("geoip.db", "geosite.db")) {
                val entry = "local:${file.name}"
                if (entry !in seenEntries) {
                    seenEntries.add(entry)
                    seenEntries.add(file.name)
                    result.add(Row(entry, getString(R.string.rule_set_custom_db_source), Kind.CUSTOM_DB, file))
                }
            }
        }

        // 3. URLs and custom rule sets from DataStore or selected
        val allCustom = (DataStore.customRuleSets + selected.filter { it.startsWith("local:") || RuleSets.isUrl(it) }).distinct()
        for (item in allCustom) {
            if (item !in seenEntries) {
                seenEntries.add(item)
                if (RuleSets.isUrl(item)) {
                    result.add(Row(item, RuleSets.source(item), Kind.URL))
                } else if (item.startsWith("local:")) {
                    val fn = item.removePrefix("local:")
                    val f = RuleSetCatalog.findLocalRuleSetFile(item, this)
                    val isDb = fn.endsWith(".db", ignoreCase = true)
                    val kind = if (isDb) Kind.CUSTOM_DB else Kind.LOCAL
                    val source = if (isDb) getString(R.string.rule_set_custom_db_source) else getString(R.string.rule_set_local_source)
                    result.add(Row(item, source, kind, f))
                }
            }
        }

        return result
    }

    @Suppress("NotifyDataSetChanged")
    private fun rebuild() {
        val query = binding.search.text?.toString()?.trim().orEmpty()
        val normalizedSelected = selected.map { if (catalog.urlOf(it) != null) it.replace(':', '-') else it }.toSet()

        when (currentTab) {
            TAB_CUSTOM -> {
                val customRows = getCustomRows()
                    .filter { query.isEmpty() || it.entry.contains(query, true) }
                val (picked, rest) = customRows.partition { isEntrySelected(it.entry) }
                rows = picked + rest
                binding.catalogEmpty.isVisible = rows.isEmpty()
            }
            TAB_SELECTED -> {
                val selectedRows = selected.map { entry ->
                    when {
                        entry.startsWith("local:") -> {
                            val fn = entry.removePrefix("local:")
                            val finalFile = RuleSetCatalog.findLocalRuleSetFile(entry, this)
                            val isDb = fn.endsWith(".db", ignoreCase = true)
                            val kind = if (isDb) Kind.CUSTOM_DB else Kind.LOCAL
                            val source = if (isDb) getString(R.string.rule_set_custom_db_source) else getString(R.string.rule_set_local_source)
                            Row(entry, source, kind, finalFile)
                        }
                        catalog.urlOf(entry) != null -> {
                            val normalized = entry.replace(':', '-')
                            val url = catalog.urlOf(entry) ?: ""
                            Row(normalized, RuleSets.source(url), Kind.KNOWN)
                        }
                        RuleSets.isUrl(entry) -> Row(entry, RuleSets.source(entry), Kind.URL)
                        else -> Row(entry, "", Kind.UNKNOWN)
                    }
                }.filter { query.isEmpty() || it.entry.contains(query, true) }
                rows = selectedRows
                binding.catalogEmpty.isVisible = rows.isEmpty()
            }
            else -> { // TAB_ALL
                val matches = catalog.search(query, Int.MAX_VALUE).map { (name, url) -> Row(name, RuleSets.source(url), Kind.KNOWN) }
                val (picked, rest) = matches.partition { it.entry in normalizedSelected }
                rows = picked + rest
                binding.catalogEmpty.isVisible = catalog.size == 0
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun isEntrySelected(entry: String): Boolean {
        if (entry in selected) return true
        val colonVariant = entry.replaceFirst('-', ':')
        return colonVariant in selected
    }

    private fun toggleSelection(entry: String) {
        val colonVariant = entry.replaceFirst('-', ':')
        val removed = selected.remove(entry) or selected.remove(colonVariant)
        if (!removed) {
            selected.add(entry)
        }
    }

    private fun finishWithResult() {
        setResult(RESULT_OK, Intent().putStringArrayListExtra(EXTRA_RULE_SETS, ArrayList(selected)))
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finishWithResult()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.rule_set_picker_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_import_file -> {
                importRuleSetLauncher.launch(arrayOf("*/*"))
                return true
            }

            R.id.action_add_url -> {
                addUrl()
                return true
            }

            R.id.action_auto_update_interval -> {
                RuleSetAutoUpdateDialog.show(this) {
                    updateSubtitle()
                }
                return true
            }

            R.id.action_refresh_catalog -> {
                refreshCatalog()
                return true
            }

            R.id.action_clear_selections -> {
                selected.clear()
                updateSubtitle()
                rebuild()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun addUrl() {
        val layout = TextInputLayout(this).apply {
            hint = getString(R.string.rule_set_add_url_hint)
        }
        val input = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isSingleLine = false
            minLines = 2
            maxLines = 8
        }
        layout.addView(input)
        val container = FrameLayout(this).apply {
            setPadding(dp2px(20), dp2px(12), dp2px(20), 0)
            addView(layout)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rule_set_add_url)
            .setMessage(R.string.rule_set_add_url_batch_hint)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.action_paste) { _, _ ->
                val clipText = io.nekohasekai.sagernet.SagerNet.clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty().trim()
                if (clipText.isNotEmpty()) {
                    input.setText(clipText)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val rawText = input.text?.toString().orEmpty().trim()
            val urls = rawText.lineSequence()
                .flatMap { it.split(',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            val validUrls = urls.filter { RuleSets.isUrl(it) }
            if (validUrls.isEmpty()) {
                layout.error = getString(R.string.rule_set_add_url_invalid)
                return@setOnClickListener
            }
            val customSet = DataStore.customRuleSets.toMutableSet()
            validUrls.forEach { u ->
                selected.add(u)
                customSet.add(u)
            }
            DataStore.customRuleSets = customSet

            updateSubtitle()
            currentTab = TAB_CUSTOM
            binding.ruleSetTabs.getTabAt(TAB_CUSTOM)?.select()
            rebuild()
            binding.list.scrollToPosition(0)
            Toast.makeText(this, getString(R.string.rule_set_added_count, validUrls.size), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
    }

    private fun refreshCatalog() {
        Toast.makeText(this, R.string.rule_set_refreshing_catalog, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                RuleSetCatalog.refreshCatalog()
            }
            catalog = withContext(Dispatchers.IO) { RuleSetCatalog.current() }
            binding.catalogEmpty.isVisible = catalog.size == 0 && currentTab == TAB_ALL
            rebuild()
            if (error == null) {
                Toast.makeText(
                    this@RuleSetPickerActivity,
                    getString(R.string.rule_set_refresh_catalog_done, catalog.size),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@RuleSetPickerActivity,
                    getString(R.string.rule_set_refresh_catalog_failed, error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showCustomDbDialog(row: Row) {
        val fileName = row.entry.removePrefix("local:")
        MaterialAlertDialogBuilder(this)
            .setTitle(fileName)
            .setMessage(getString(R.string.rule_set_custom_db_dialog_msg, fileName))
            .setPositiveButton(R.string.rule_set_custom_db_search_built_in) { _, _ ->
                currentTab = TAB_ALL
                binding.ruleSetTabs.getTabAt(TAB_ALL)?.select()
                binding.search.requestFocus()
            }
            .setNeutralButton(if (isEntrySelected(row.entry)) R.string.rule_set_deselect else R.string.rule_set_select_anyway) { _, _ ->
                toggleSelection(row.entry)
                adapter.notifyDataSetChanged()
                updateSubtitle()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowHolder>(), FastScrollRecyclerView.SectionedAdapter {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RowHolder(LayoutRuleSetItemBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: RowHolder, position: Int) = holder.bind(rows[position])

        override fun getItemCount() = rows.size

        override fun getSectionName(position: Int): String {
            if (position !in rows.indices) return "*"
            val row = rows[position]
            return if (row.kind == Kind.KNOWN) {
                row.entry.removePrefix("geoip-").removePrefix("geosite-").take(1).uppercase()
            } else {
                "*"
            }
        }
    }

    private inner class RowHolder(val binding: LayoutRuleSetItemBinding) : RecyclerView.ViewHolder(binding.root) {
        private lateinit var row: Row
        private val sourceColors = binding.source.textColors

        init {
            binding.root.setOnClickListener {
                if (row.kind == Kind.CUSTOM_DB) {
                    showCustomDbDialog(row)
                } else {
                    toggleSelection(row.entry)
                    binding.check.isChecked = isEntrySelected(row.entry)
                    updateSubtitle()
                }
            }

            binding.root.setOnLongClickListener {
                val targetFile = row.file
                if ((row.kind == Kind.LOCAL || row.kind == Kind.CUSTOM_DB) && targetFile != null && targetFile.isFile) {
                    MaterialAlertDialogBuilder(this@RuleSetPickerActivity)
                        .setTitle(R.string.delete)
                        .setMessage(getString(R.string.rule_set_delete_confirm))
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            targetFile.delete()
                            selected.remove(row.entry)
                            selected.remove(targetFile.name)
                            val set = DataStore.customRuleSets.toMutableSet()
                            set.remove(targetFile.name)
                            set.remove(row.entry)
                            DataStore.customRuleSets = set
                            updateSubtitle()
                            rebuild()
                            Toast.makeText(this@RuleSetPickerActivity, getString(R.string.rule_set_deleted, targetFile.name), Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                } else if (row.kind == Kind.URL) {
                    val displayName = RuleSets.shortName(row.entry)
                    MaterialAlertDialogBuilder(this@RuleSetPickerActivity)
                        .setTitle(R.string.delete)
                        .setMessage(getString(R.string.rule_set_delete_url_confirm, displayName))
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            selected.remove(row.entry)
                            val set = DataStore.customRuleSets.toMutableSet()
                            set.remove(row.entry)
                            DataStore.customRuleSets = set
                            updateSubtitle()
                            rebuild()
                            Toast.makeText(this@RuleSetPickerActivity, getString(R.string.rule_set_deleted, displayName), Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                } else {
                    false
                }
            }
        }

        fun bind(row: Row) {
            this.row = row
            binding.name.text = when {
                row.kind == Kind.URL -> RuleSets.shortName(row.entry)
                row.entry.startsWith("local:") -> row.entry.removePrefix("local:")
                else -> row.entry
            }

            val externalDir = io.nekohasekai.sagernet.SagerNet.application.getExternalFilesDir(null) ?: io.nekohasekai.sagernet.SagerNet.application.filesDir
            val srsDir = File(externalDir, "srs")
            val effectiveCache = if (row.kind == Kind.KNOWN || row.kind == Kind.URL) {
                RuleSets.findCachedSrsFile(srsDir, row.entry)
            } else {
                null
            }
            val cacheSizeStr = if (effectiveCache != null) " · " + android.text.format.Formatter.formatFileSize(this@RuleSetPickerActivity, effectiveCache.length()) else ""

            binding.source.text = when (row.kind) {
                Kind.KNOWN -> row.source + (if (effectiveCache != null) " [已缓存$cacheSizeStr]" else "")
                Kind.URL -> getString(R.string.rule_set_url_source, row.source) + (if (effectiveCache != null) " [已缓存$cacheSizeStr]" else " [未缓存]")
                Kind.LOCAL -> getString(R.string.rule_set_local_source)
                Kind.CUSTOM_DB -> getString(R.string.rule_set_custom_db_source)
                Kind.UNKNOWN -> getString(R.string.rule_set_unknown)
            }
            if (row.kind == Kind.UNKNOWN) {
                binding.source.setTextColor(ContextCompat.getColor(this@RuleSetPickerActivity, R.color.color_route_block))
            } else {
                binding.source.setTextColor(sourceColors)
            }
            binding.check.isChecked = isEntrySelected(row.entry)
        }
    }
}
