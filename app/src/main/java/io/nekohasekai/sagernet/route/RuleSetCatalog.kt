package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.tryProxyOutbound
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma
import java.io.File
import java.io.IOException
import java.util.regex.Pattern

/** The name -> .srs URL map of `routeprofiles/srslist.h` (~2300+ remote rule-sets). */
class RuleSetCatalog(entries: List<Pair<String, String>>) {
    private val map = LinkedHashMap<String, String>()

    init {
        for ((name, url) in entries) if (!map.containsKey(name)) map[name] = url
    }

    private val nameList: List<String> = ArrayList(map.keys)

    val size: Int get() = map.size

    fun urlOf(name: String): String? {
        val trimmed = name.trim()
        return map[trimmed] ?: map[trimmed.replace(':', '-')]
    }

    fun names(): List<String> = nameList

    /** Case-insensitive: exact match, then prefix matches, then substring matches, each in list order. */
    fun search(query: String, limit: Int): List<Pair<String, String>> {
        if (limit <= 0) return emptyList()
        val q = query.trim()
        if (q.isEmpty()) return map.entries.take(limit).map { it.key to it.value }
        val normalizedQ = q.replace(':', '-')
        val exact = ArrayList<Pair<String, String>>()
        val prefix = ArrayList<Pair<String, String>>()
        val contains = ArrayList<Pair<String, String>>()
        for ((name, url) in map) {
            when {
                name.equals(q, ignoreCase = true) || name.equals(normalizedQ, ignoreCase = true) -> exact.add(name to url)
                name.startsWith(q, ignoreCase = true) || name.startsWith(normalizedQ, ignoreCase = true) -> prefix.add(name to url)
                name.contains(q, ignoreCase = true) || name.contains(normalizedQ, ignoreCase = true) -> contains.add(name to url)
            }
        }
        return (exact + prefix + contains).take(limit)
    }

    companion object {
        private const val DIR = "routeprofiles"
        const val SRS_LIST = "srslist.h"
        private const val SRS_LIST_URL = "https://raw.githubusercontent.com/throneproj/routeprofiles/rule-set/srslist.h"
        private val ENTRY = Pattern.compile("\\{\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*\\}")

        @Volatile
        private var cached: RuleSetCatalog? = null

        fun parseSrsList(text: String): RuleSetCatalog {
            val matcher = ENTRY.matcher(text)
            val entries = ArrayList<Pair<String, String>>()
            while (matcher.find()) entries.add(matcher.group(1)!! to matcher.group(2)!!)
            return RuleSetCatalog(entries)
        }

        private fun srsListFile(): File? = runCatching {
            File(File(SagerNet.application.filesDir, DIR), SRS_LIST)
        }.getOrNull()

        private fun readBundledAsset(): String? {
            val appAsset = runCatching {
                SagerNet.application.assets.open("$DIR/$SRS_LIST").bufferedReader().use { it.readText() }
            }.getOrNull()
            if (!appAsset.isNullOrEmpty()) return appAsset

            // 兼顾 JVM 单元测试无 Application 上下文环境
            val localFile = sequenceOf(
                File("src/main/assets/$DIR/$SRS_LIST"),
                File("app/src/main/assets/$DIR/$SRS_LIST"),
                File("../app/src/main/assets/$DIR/$SRS_LIST")
            ).firstOrNull { it.isFile }
            return localFile?.runCatching { readText() }?.getOrNull()
        }

        fun current(): RuleSetCatalog {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val runtimeFile = srsListFile()
                val text = if (runtimeFile != null && runtimeFile.isFile) {
                    runCatching { runtimeFile.readText() }.getOrNull()
                } else {
                    null
                } ?: readBundledAsset().orEmpty()
                val parsed = parseSrsList(text)
                val finalCatalog = if (parsed.size > 0) {
                    parsed
                } else {
                    parseSrsList(readBundledAsset().orEmpty())
                }
                cached = finalCatalog
                return finalCatalog
            }
        }

        fun invalidate() {
            synchronized(this) {
                cached = null
            }
        }

        /**
         * Resolves any rule-set entry (catalog name like `geoip-ad` / `geosite-cn` / `geosite:cn`,
         * direct `.srs` URL, or legacy `rsip:` / `rssite:` URL) to `(url, isIPRuleset, tag)`.
         */
        fun resolveEntry(origEntry: String): Triple<String, Boolean, String> {
            val trimmed = origEntry.trim()
            if (trimmed.startsWith("local:")) {
                val pathOrName = trimmed.removePrefix("local:")
                val rulesDir = File(SagerNet.application.getExternalFilesDir("rulesets") ?: SagerNet.application.filesDir, "rulesets")
                val file = if (File(pathOrName).isAbsolute) File(pathOrName) else File(rulesDir, pathOrName)
                val isIp = pathOrName.contains("geoip", ignoreCase = true) || pathOrName.contains("ip", ignoreCase = true)
                val safeTag = "local-" + file.nameWithoutExtension.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                return Triple(file.absolutePath, isIp, safeTag)
            }
            val rulesDir = File(SagerNet.application.getExternalFilesDir("rulesets") ?: SagerNet.application.filesDir, "rulesets")
            val candidateFile = File(rulesDir, trimmed)
            if (candidateFile.isFile || (File(trimmed).isAbsolute && File(trimmed).isFile)) {
                val file = if (candidateFile.isFile) candidateFile else File(trimmed)
                val isIp = file.name.contains("geoip", ignoreCase = true) || file.name.contains("ip", ignoreCase = true)
                val safeTag = "local-" + file.nameWithoutExtension.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                return Triple(file.absolutePath, isIp, safeTag)
            }
            val catalog = current()
            catalog.urlOf(trimmed)?.let { url ->
                val isIp = RuleSets.isIpEntry(trimmed, url)
                val tag = RuleSets.tagFor(trimmed)
                return Triple(url, isIp, tag)
            }
            if (RuleSets.isUrl(trimmed)) {
                val url = RuleSets.cleanUrl(trimmed)
                val isIp = RuleSets.isIpEntry(trimmed, url)
                val tag = RuleSets.tagFor(trimmed)
                return Triple(url, isIp, tag)
            }
            val normalized = trimmed.replace(':', '-')
            if (normalized.startsWith("geoip-")) {
                val code = normalized.removePrefix("geoip-")
                val url = "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/sing/geo/geoip/$code.srs"
                return Triple(url, true, normalized)
            }
            if (normalized.startsWith("geosite-")) {
                val code = normalized.removePrefix("geosite-")
                val url = "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/sing/geo/geosite/$code.srs"
                return Triple(url, false, normalized)
            }
            val safeTag = "rs-" + kotlin.math.abs(trimmed.hashCode())
            return Triple(trimmed, trimmed.contains("ip", ignoreCase = true), safeTag)
        }

        /**
         * Refreshes `srslist.h` from GitHub (with automatic jsDelivr mirror fallbacks).
         * Returns `null` on success, or an error message on failure.
         */
        fun refreshCatalog(): String? {
            val urls = listOf(
                SRS_LIST_URL,
                RuleSets.mirrorLink(SRS_LIST_URL, RuleSets.MIRROR_CLOUDFLARE),
                RuleSets.mirrorLink(SRS_LIST_URL, RuleSets.MIRROR_FASTLY),
                RuleSets.mirrorLink(SRS_LIST_URL, RuleSets.MIRROR_GCORE)
            ).distinct()
            var lastError = "Unable to download $SRS_LIST"
            for (candidateUrl in urls) {
                for (useProxy in listOf(DataStore.serviceState.started, false).distinct()) {
                    val client = Libcore.newHttpClient().apply {
                        modernTLS()
                        keepAlive()
                        if (useProxy) tryProxyOutbound()
                    }
                    try {
                        val response = client.newRequest().apply {
                            setURL(candidateUrl)
                        }.execute()
                        val body = Util.getStringBox(response.contentString)
                        val parsed = parseSrsList(body)
                        if (parsed.size > 0) {
                            val dst = srsListFile() ?: return "Unable to determine storage directory"
                            dst.parentFile?.mkdirs()
                            val tmp = File(dst.parentFile, dst.name + ".tmp")
                            tmp.writeText(body)
                            if (!tmp.renameTo(dst)) {
                                dst.writeText(body)
                                tmp.delete()
                            }
                            synchronized(this) {
                                cached = parsed
                            }
                            return null
                        }
                    } catch (e: Exception) {
                        lastError = e.readableMessage
                    } finally {
                        client.close()
                    }
                }
            }
            return lastError
        }

        /**
         * Downloads/refreshes all remote `.srs` rule-sets referenced by the user's routing rules
         * into `externalFilesDir/srs/<safeTag>.srs`.
         * Returns `(updatedCount, errorList)`.
         */
        fun refreshUsedRuleSets(onProgress: ((current: Int, total: Int, name: String) -> Unit)? = null): Pair<Int, List<String>> {
            val rules = SagerDatabase.rulesDao.allRules()
            val entries = LinkedHashSet<String>()
            for (rule in rules) {
                rule.ruleset.listByLineOrComma().forEach { if (it.isNotBlank()) entries.add(it.trim()) }
                rule.domains.listByLineOrComma()
                    .filter { it.startsWith("geosite:") || it.startsWith("geosite-") }
                    .forEach { entries.add(it.trim()) }
                rule.ip.listByLineOrComma()
                    .filter { (it.startsWith("geoip:") || it.startsWith("geoip-")) && it != "geoip:private" && it != "geoip-private" }
                    .forEach { entries.add(it.trim()) }
            }
            if (entries.isEmpty()) return 0 to emptyList()

            val externalDir = SagerNet.application.getExternalFilesDir(null) ?: SagerNet.application.filesDir
            val srsDir = File(externalDir, "srs").apply { mkdirs() }
            var updated = 0
            val errors = ArrayList<String>()
            val list = entries.toList()

            for ((index, entry) in list.withIndex()) {
                val short = RuleSets.shortName(entry)
                onProgress?.invoke(index + 1, list.size, short)
                val resolved = try {
                    resolveEntry(entry)
                } catch (e: Exception) {
                    errors.add("$short: ${e.readableMessage}")
                    continue
                }
                val url = resolved.first
                val tag = resolved.third
                val safeTag = tag.replace(Regex("[:/\\\\?&=]"), "_")
                val altTag = tag.replace(':', '-')
                val dstFile = File(srsDir, "$safeTag.srs")
                val altFile = File(srsDir, "$altTag.srs")
                val tmpFile = File(srsDir, "$safeTag.srs.tmp")

                val candidateUrls = listOf(
                    url,
                    RuleSets.mirrorLink(url, RuleSets.MIRROR_CLOUDFLARE),
                    RuleSets.mirrorLink(url, RuleSets.MIRROR_FASTLY),
                    RuleSets.mirrorLink(url, RuleSets.MIRROR_GCORE)
                ).distinct()

                var downloaded = false
                var lastErr = "Download failed"
                outer@ for (candidateUrl in candidateUrls) {
                    for (useProxy in listOf(DataStore.serviceState.started, false).distinct()) {
                        val client = Libcore.newHttpClient().apply {
                            modernTLS()
                            keepAlive()
                            if (useProxy) tryProxyOutbound()
                        }
                        try {
                            val response = client.newRequest().apply {
                                setURL(candidateUrl)
                            }.execute()
                            response.writeTo(tmpFile.canonicalPath)
                            if (tmpFile.isFile && tmpFile.length() > 0L) {
                                if (!tmpFile.renameTo(dstFile)) {
                                    tmpFile.copyTo(dstFile, overwrite = true)
                                    tmpFile.delete()
                                }
                                if (altFile.canonicalPath != dstFile.canonicalPath) {
                                    dstFile.copyTo(altFile, overwrite = true)
                                }
                                downloaded = true
                                break@outer
                            }
                        } catch (e: Exception) {
                            lastErr = e.readableMessage
                        } finally {
                            client.close()
                        }
                    }
                }
                if (downloaded) {
                    updated++
                } else {
                    tmpFile.delete()
                    errors.add("$short: $lastErr")
                }
            }
            return updated to errors
        }
    }
}
