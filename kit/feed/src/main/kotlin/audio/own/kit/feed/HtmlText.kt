package audio.own.kit.feed

/**
 * Show notes come as HTML (often inside CDATA). The app shows them as text with paragraphs, so
 * this turns markup into lines without a web view, off the main thread. Links are kept as text;
 * the episode page finds them again.
 */
object HtmlText {
    // Blocks become line breaks, list items get a bullet.
    private val rules: List<Pair<Regex, String>> = listOf(
        "(?i)<br\\s*/?>" to "\n", "(?i)</p\\s*>" to "\n\n", "(?i)</div\\s*>" to "\n", "(?i)</h[1-6]\\s*>" to "\n\n",
        "(?i)<li[^>]*>" to "\n• ", "(?i)</li\\s*>" to "", "(?i)</(ul|ol)\\s*>" to "\n\n",
        "(?is)<(script|style)[^>]*>.*?</\\1\\s*>" to "", "(?s)<!--.*?-->" to "", "(?s)<[^>]+>" to "",
    ).map { (pattern, replacement) -> Regex(pattern) to replacement }

    fun plain(html: String): String {
        if (!html.contains('<') && !html.contains('&')) return collapse(html)
        var text = html
        for ((pattern, replacement) in rules) text = pattern.replace(text, replacement)
        return collapse(decodeEntities(text))
    }

    /** The named entities feeds use, and every numeric one. */
    fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index]
            val semicolon = if (character == '&') semicolonNear(text, index) else null
            val decoded = semicolon?.let { entity(text.substring(index + 1, it)) }
            if (semicolon != null && decoded != null) {
                out.append(decoded)
                index = semicolon + 1
            } else {
                out.append(character)
                index++
            }
        }
        return out.toString()
    }

    // Entities are short: only a ';' within the next eleven characters closes one.
    private fun semicolonNear(text: String, ampersand: Int): Int? {
        for (index in ampersand + 1 until minOf(text.length, ampersand + 12)) {
            if (text[index] == ';') return index
        }
        return null
    }

    private fun entity(name: String): String? {
        if (name.startsWith("#")) {
            val digits = name.drop(1)
            val value = if (digits.startsWith("x") || digits.startsWith("X")) digits.drop(1).toIntOrNull(16) else digits.toIntOrNull()
            if (value == null || value < 0 || !Character.isValidCodePoint(value) || value in 0xD800..0xDFFF) return null
            return String(Character.toChars(value))
        }
        return named[name]
    }

    internal val named: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–", "mdash" to "—",
        "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»",
        "bdquo" to "„", "copy" to "©", "reg" to "®", "trade" to "™", "euro" to "€", "middot" to "·", "bull" to "•", "deg" to "°",
    )

    private val spaces = Regex("[ \t ]+")
    private val spacedBreak = Regex(" *\n *")
    private val manyBreaks = Regex("\n{3,}")

    /** Runs of spaces become one, three or more line breaks become two, ends are trimmed. */
    private fun collapse(input: String): String {
        var text = input.replace("\r\n", "\n")
        text = spaces.replace(text, " ")
        text = spacedBreak.replace(text, "\n")
        text = manyBreaks.replace(text, "\n\n")
        return text.trim()
    }
}
