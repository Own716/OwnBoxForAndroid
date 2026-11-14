package io.nekohasekai.sagernet.bg.autoselector

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.proto.BoxInstance

/**
 * Thin Kotlin wrapper over libcore's `BoxInstance.queryAutoSelectorsJSON`
 * and `BoxInstance.autoSelectorAction`.
 */
object AutoSelectorCore {

    fun queryStatuses(
        instance: BoxInstance?,
        withMembers: Boolean,
        buildsByTag: Map<String, AutoSelectorBuild> = emptyMap(),
    ): List<AutoSelectorStatus> {
        if (instance == null || !instance.isInitialized()) return emptyList()
        val raw = runCatching { instance.box.queryAutoSelectorsJSON(withMembers) }.getOrNull() ?: return emptyList()
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
        return runCatching {
            val arr = JsonParser.parseString(trimmed).asJsonArray
            val list = ArrayList<AutoSelectorStatus>(arr.size())
            for (elem in arr) {
                if (!elem.isJsonObject) continue
                val obj = elem.asJsonObject
                val tag = obj.get("tag")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val build = buildsByTag[tag]
                list.add(AutoSelectorStatus.parse(elem.toString(), build))
            }
            list
        }.getOrDefault(emptyList())
    }

    fun select(instance: BoxInstance?, groupTag: String, memberTag: String) {
        if (instance == null || !instance.isInitialized()) {
            throw IllegalStateException("box not running")
        }
        val err = instance.box.autoSelectorAction(groupTag, "select", memberTag)
        if (!err.isNullOrEmpty()) {
            throw IllegalStateException(err)
        }
    }

    fun recheck(instance: BoxInstance?, groupTag: String) {
        if (instance == null || !instance.isInitialized()) {
            throw IllegalStateException("box not running")
        }
        val err = instance.box.autoSelectorAction(groupTag, "recheck", "")
        if (!err.isNullOrEmpty()) {
            throw IllegalStateException(err)
        }
    }
}
