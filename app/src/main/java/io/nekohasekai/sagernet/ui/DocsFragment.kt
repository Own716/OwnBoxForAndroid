package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.ItemDocCardBinding
import io.nekohasekai.sagernet.databinding.ItemDocHeaderBinding
import io.nekohasekai.sagernet.databinding.LayoutDocsBinding
import io.nekohasekai.sagernet.widget.ListListener

class DocsFragment : ToolbarFragment(R.layout.layout_docs) {

    private var _binding: LayoutDocsBinding? = null
    private val binding get() = _binding!!

    private val displayItems = ArrayList<DocListItem>()
    private val expandedIds = HashSet<String>()
    private lateinit var adapter: DocsAdapter

    private var selectedCategoryId: String = DocsCatalog.CATEGORY_ALL
    private var currentQuery: String = ""
    private var allChipId: Int = View.NO_ID

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        _binding = LayoutDocsBinding.bind(view)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_documentation)

        adapter = DocsAdapter()
        binding.docsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.docsRecycler.adapter = adapter

        setupCategoryChips()
        setupSearchAndActions()
        applyFilters(scrollToTop = false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupCategoryChips() {
        val chipGroup = binding.categoryChipGroup
        chipGroup.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        fun addChip(categoryId: String, label: String, checked: Boolean): Chip {
            val chip = inflater.inflate(
                R.layout.item_doc_category_chip,
                chipGroup,
                false,
            ) as Chip
            chip.id = View.generateViewId()
            chip.tag = categoryId
            chip.text = label
            chip.isChecked = checked
            chipGroup.addView(chip)
            return chip
        }

        allChipId = addChip(DocsCatalog.CATEGORY_ALL, "全部", checked = true).id
        for (category in DocsCatalog.categories) {
            addChip(category.id, category.chipLabel, checked = false)
        }

        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val checkedChip = group.findViewById<Chip>(checkedId) ?: return@setOnCheckedStateChangeListener
            val newCategoryId = checkedChip.tag as? String ?: DocsCatalog.CATEGORY_ALL
            if (newCategoryId != selectedCategoryId) {
                selectedCategoryId = newCategoryId
                applyFilters(scrollToTop = true)
            }
        }
    }

    private fun setupSearchAndActions() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty()
                binding.btnClearSearch.isVisible = query.isNotEmpty()
                if (query != currentQuery) {
                    currentQuery = query
                    applyFilters(scrollToTop = true)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            binding.searchInput.text?.clear()
            binding.searchInput.clearFocus()
        }

        binding.btnResetFilter.setOnClickListener {
            resetFilters()
        }

        binding.btnToggleExpandAll.setOnClickListener {
            toggleExpandAllVisible()
        }
    }

    private fun resetFilters() {
        selectedCategoryId = DocsCatalog.CATEGORY_ALL
        if (allChipId != View.NO_ID) {
            binding.categoryChipGroup.check(allChipId)
        }
        if (!binding.searchInput.text.isNullOrEmpty()) {
            currentQuery = ""
            binding.searchInput.text?.clear()
        } else {
            currentQuery = ""
        }
        binding.btnClearSearch.isVisible = false
        binding.searchInput.clearFocus()
        applyFilters(scrollToTop = true)
    }

    private fun applyFilters(scrollToTop: Boolean) {
        val filtered = DocsCatalog.filter(selectedCategoryId, currentQuery)
        displayItems.clear()
        displayItems.addAll(filtered)
        adapter.notifyDataSetChanged()

        if (scrollToTop && filtered.isNotEmpty()) {
            binding.docsRecycler.scrollToPosition(0)
        }
        updateResultControls()
    }

    private fun visibleEntryIds(): List<String> {
        return displayItems.mapNotNull { (it as? DocListItem.Entry)?.entry?.id }
    }

    private fun toggleExpandAllVisible() {
        val visibleIds = visibleEntryIds()
        if (visibleIds.isEmpty()) return

        val allExpanded = visibleIds.all { it in expandedIds }
        if (allExpanded) {
            expandedIds.removeAll(visibleIds.toSet())
        } else {
            expandedIds.addAll(visibleIds)
        }
        adapter.notifyDataSetChanged()
        updateResultControls()
    }

    private fun updateResultControls() {
        val visibleIds = visibleEntryIds()
        val entryCount = visibleIds.size
        val isFiltered = selectedCategoryId != DocsCatalog.CATEGORY_ALL || currentQuery.isNotEmpty()

        binding.docsRecycler.isVisible = entryCount > 0
        binding.emptyState.isVisible = entryCount == 0

        binding.docsResultSummary.text = when {
            entryCount == 0 -> "无匹配结果"
            isFiltered -> "找到 $entryCount 条相关内容"
            else -> "共 $entryCount 篇文档 · 点击卡片展开详情"
        }

        binding.btnToggleExpandAll.isVisible = entryCount > 0
        if (entryCount > 0) {
            val allExpanded = visibleIds.all { it in expandedIds }
            binding.btnToggleExpandAll.text = if (allExpanded) "全部收起" else "全部展开"
        }
    }

    inner class DocsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int {
            return when (displayItems[position]) {
                is DocListItem.Header -> 0
                is DocListItem.Entry -> 1
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
                is DocListItem.Header -> (holder as HeaderHolder).bind(item.category)
                is DocListItem.Entry -> (holder as CardHolder).bind(item.entry)
            }
        }

        override fun getItemCount(): Int = displayItems.size
    }

    inner class HeaderHolder(private val binding: ItemDocHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(category: DocCategory) {
            binding.headerTitle.text = category.headerTitle
            binding.headerDesc.text = category.headerDesc
        }
    }

    inner class CardHolder(private val binding: ItemDocCardBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: DocEntry) {
            val isExpanded = entry.id in expandedIds
            binding.docItemTitle.text = entry.title
            binding.docRecommendBadge.text = entry.badge
            binding.docSummary.text = entry.summary
            binding.docSummary.maxLines = if (isExpanded) Int.MAX_VALUE else 2
            binding.docSummary.ellipsize = if (isExpanded) null else TextUtils.TruncateAt.END
            binding.docExpandIcon.rotation = if (isExpanded) 180f else 0f
            binding.docDetailContainer.isVisible = isExpanded

            binding.docDesc.text = entry.desc
            binding.docNotes.text = entry.notes
            binding.docRecommendation.text = entry.recommendation

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (entry.id in expandedIds) {
                    expandedIds.remove(entry.id)
                } else {
                    expandedIds.add(entry.id)
                }
                adapter.notifyItemChanged(pos)
                updateResultControls()
            }
        }
    }
}
