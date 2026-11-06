package io.nekohasekai.sagernet.widget

import android.app.Activity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.ServiceDiagnosticsManager

object PinnedDiagnosticsBottomSheet {

    fun show(activity: Activity, onSaved: (List<String>) -> Unit) {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.layout_pinned_diagnostics_sheet, null)
        dialog.setContentView(view)

        val btnSave = view.findViewById<MaterialButton>(R.id.btn_save_pinned)
        val tvTip = view.findViewById<TextView>(R.id.tv_pinned_count_tip)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_pinned_platforms)

        val allPlatforms = ServiceDiagnosticsManager.createInitialList()
        val currentPinned = DataStore.getPinnedDiagnostics().toMutableSet()

        fun updateTip() {
            tvTip.text = "已选 ${currentPinned.size}/3 项，进入网络诊断时将优先自动测试"
        }
        updateTip()

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val item = LayoutInflater.from(parent.context).inflate(R.layout.item_pinned_diagnostic_select, parent, false)
                return object : RecyclerView.ViewHolder(item) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = allPlatforms[position]
                val v = holder.itemView
                val ivIcon = v.findViewById<ImageView>(R.id.iv_platform_icon)
                val tvName = v.findViewById<TextView>(R.id.tv_platform_name)
                val tvCategory = v.findViewById<TextView>(R.id.tv_platform_category)
                val cb = v.findViewById<MaterialCheckBox>(R.id.cb_platform_selected)

                ivIcon.setImageResource(item.iconRes)
                tvName.text = item.name
                tvCategory.text = item.category.label
                cb.isChecked = currentPinned.contains(item.id)

                v.setOnClickListener {
                    if (currentPinned.contains(item.id)) {
                        if (currentPinned.size <= 1) {
                            Toast.makeText(activity, "至少保留 1 项固定测试", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        currentPinned.remove(item.id)
                        cb.isChecked = false
                    } else {
                        if (currentPinned.size >= 3) {
                            Toast.makeText(activity, "最多只能选择 3 项固定测试", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        currentPinned.add(item.id)
                        cb.isChecked = true
                    }
                    updateTip()
                }
            }

            override fun getItemCount(): Int = allPlatforms.size
        }

        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter

        btnSave.setOnClickListener {
            val list = currentPinned.toList()
            DataStore.setPinnedDiagnostics(list)
            dialog.dismiss()
            onSaved(list)
        }

        dialog.show()
    }
}
