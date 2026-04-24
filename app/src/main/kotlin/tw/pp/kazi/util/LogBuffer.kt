package tw.pp.kazi.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Locale

object LogBuffer {

    enum class Level { D, I, W, E }

    data class Entry(
        val timestamp: Long,
        val level: Level,
        val message: String,
        val stackTrace: String? = null,
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun append(level: Level, message: String, throwable: Throwable? = null) {
        val entry = Entry(
            timestamp = System.currentTimeMillis(),
            level = level,
            message = message,
            stackTrace = throwable?.stackTraceToString()?.take(STACK_TRACE_MAX_CHARS),
        )
        _entries.update { list ->
            val next = ArrayList<Entry>(list.size + 1)
            next.addAll(list)
            next.add(entry)
            if (next.size > MAX_ENTRIES) {
                next.subList(0, next.size - MAX_ENTRIES).clear()
            }
            next
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    fun format(entry: Entry): String {
        val time = TIME_FORMATTER.get()!!.format(entry.timestamp)
        val base = "[${entry.level.name}] $time  ${entry.message}"
        return if (entry.stackTrace != null) "$base\n${entry.stackTrace}" else base
    }

    private const val MAX_ENTRIES = 300
    private const val STACK_TRACE_MAX_CHARS = 4_000

    // SimpleDateFormat 非 thread-safe，包進 ThreadLocal
    private val TIME_FORMATTER = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}
