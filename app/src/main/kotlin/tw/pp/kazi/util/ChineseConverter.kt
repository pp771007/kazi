package tw.pp.kazi.util

import android.content.Context

object ChineseConverter {

    @Volatile private var charMap: Map<Char, Char>? = null
    @Volatile private var phraseMap: Map<String, String>? = null
    @Volatile private var maxPhraseLen: Int = 1

    fun preload(context: Context) {
        if (charMap != null && phraseMap != null) return
        val charResult = loadCharMap(context)
        val phraseResult = loadPhraseMap(context)
        charMap = charResult
        phraseMap = phraseResult
        maxPhraseLen = phraseResult.keys.maxOfOrNull { it.length } ?: 1
    }

    fun toSimplified(input: String, context: Context): String {
        if (input.isEmpty()) return input
        preload(context)
        val chars = charMap ?: return input
        val phrases = phraseMap ?: emptyMap()

        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val remaining = input.length - i
            val maxWindow = minOf(maxPhraseLen, remaining)
            var matched = false
            for (len in maxWindow downTo 2) {
                val slice = input.substring(i, i + len)
                val hit = phrases[slice]
                if (hit != null) {
                    out.append(hit)
                    i += len
                    matched = true
                    break
                }
            }
            if (matched) continue

            val c = input[i]
            val mapped = chars[c]
            out.append(mapped ?: c)
            i++
        }
        return out.toString()
    }

    private fun loadCharMap(context: Context): Map<Char, Char> {
        val map = HashMap<Char, Char>(CHAR_MAP_INITIAL_CAPACITY)
        runCatching {
            context.assets.open(CHAR_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.forEachLine { line ->
                    if (line.isBlank() || line.startsWith("#")) return@forEachLine
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEachLine
                    val keyStr = line.substring(0, tab).trim()
                    val valuePart = line.substring(tab + 1).trim()
                    val firstValue = valuePart.substringBefore(' ')
                    if (keyStr.length == 1 && firstValue.isNotEmpty()) {
                        val keyChar = keyStr[0]
                        val valChar = firstValue.codePointAt(0)
                        // 只處理 BMP 單字元映射，略過 surrogate pair 的罕見字
                        if (valChar <= 0xFFFF) {
                            map[keyChar] = valChar.toChar()
                        }
                    }
                }
            }
        }.onFailure { Logger.w("loadCharMap failed: ${it.message}") }
        return map
    }

    private fun loadPhraseMap(context: Context): Map<String, String> {
        val map = HashMap<String, String>(PHRASE_MAP_INITIAL_CAPACITY)
        runCatching {
            context.assets.open(PHRASE_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.forEachLine { line ->
                    if (line.isBlank() || line.startsWith("#")) return@forEachLine
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEachLine
                    val key = line.substring(0, tab).trim()
                    val value = line.substring(tab + 1).substringBefore(' ').trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        map[key] = value
                    }
                }
            }
        }.onFailure { Logger.w("loadPhraseMap failed: ${it.message}") }
        return map
    }

    private const val CHAR_ASSET = "t2s_chars.txt"
    private const val PHRASE_ASSET = "t2s_phrases.txt"
    private const val CHAR_MAP_INITIAL_CAPACITY = 4200
    private const val PHRASE_MAP_INITIAL_CAPACITY = 300
}
