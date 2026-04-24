package tw.pp.kazi.data

enum class ViewMode(
    val key: String,
    val columns: Int,
    val aspectRatio: Float,
    val label: String,
    val emoji: String,
) {
    Portrait("portrait", 6, 2f / 3f, "直立", "📱"),
    Landscape("landscape", 4, 16f / 9f, "橫躺", "🖥"),
    Square("square", 5, 1f, "方形", "⬜");

    companion object {
        val Default: ViewMode = Portrait
        fun fromKey(key: String?): ViewMode =
            entries.firstOrNull { it.key == key } ?: Default
    }
}
