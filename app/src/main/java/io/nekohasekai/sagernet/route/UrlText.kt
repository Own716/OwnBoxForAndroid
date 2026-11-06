package io.nekohasekai.sagernet.route

import java.io.ByteArrayOutputStream

/** URL parsing and file-name extraction helpers for remote rule-sets without URI strictness. */
internal object UrlText {
    private val URL = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)([^?#]*)")

    class Parts(val scheme: String, val host: String, val path: String)

    fun parse(url: String): Parts? {
        val m = URL.find(url) ?: return null
        val authority = m.groupValues[2].substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        return Parts(m.groupValues[1].lowercase(), host.lowercase(), m.groupValues[3])
    }

    /** The last path segment, fully percent-decoded. */
    fun fileName(url: String): String = parse(url)?.path?.substringAfterLast('/')?.let { percentDecode(it) } ?: ""

    fun percentDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && hex(s[i + 1]) >= 0 && hex(s[i + 2]) >= 0) {
                out.write(hex(s[i + 1]) * 16 + hex(s[i + 2]))
                i += 3
                continue
            }
            val cp = s.codePointAt(i)
            out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
            i += Character.charCount(cp)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
