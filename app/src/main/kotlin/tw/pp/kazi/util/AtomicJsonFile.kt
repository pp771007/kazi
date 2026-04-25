package tw.pp.kazi.util

import java.io.File
import java.io.FileOutputStream

/**
 * 原子寫檔：先寫到 .tmp 然後 rename 過去。
 * 跟原本實作的差別：
 * - 用 FileOutputStream + fd.sync() 強制把 buffer 寫進實體 storage（防斷電）
 * - rename 失敗才 fall back 直接覆寫，不會在 writeText() exception 時誤判
 * - 所有 IO exception 都會 propagate 出去（caller 自己 catch + 處理），不再 silent
 */
fun File.atomicWriteText(content: String) {
    val parent = parentFile ?: throw IllegalStateException("File has no parent: $absolutePath")
    parent.mkdirs()
    val tmp = File(parent, "$name.tmp")
    FileOutputStream(tmp).use { fos ->
        fos.write(content.toByteArray(Charsets.UTF_8))
        fos.fd.sync()
    }
    // 注意：rename 失敗才 fall back；writeText 出錯不該 fall back（tmp 是壞的）
    if (!tmp.renameTo(this)) {
        FileOutputStream(this).use { fos ->
            fos.write(tmp.readBytes())
            fos.fd.sync()
        }
        tmp.delete()
    }
}

/**
 * 嘗試讀檔 + 用 decoder 解析。解析失敗時把壞檔案 rename 成 .corrupt-<ts>，
 * 讓使用者有機會手動救回，並回傳 fallback。
 *
 * 沒檔案 / 空檔案 → 直接回 fallback（不算錯誤）
 * 解析失敗 → log warning + rename + 回 fallback
 */
inline fun <T> File.readJsonOrBackup(
    fallback: () -> T,
    decoder: (String) -> T,
): T {
    val raw = runCatching { readText() }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: return fallback()
    return runCatching { decoder(raw) }.getOrElse { e ->
        Logger.w("readJsonOrBackup failed for $absolutePath: ${e.message}")
        markCorrupt()
        fallback()
    }
}

/** 把疑似壞掉的檔案 rename 成 .corrupt-<timestamp>，讓下次 load 從乾淨狀態開始 */
fun File.markCorrupt() {
    val ts = System.currentTimeMillis()
    val target = File(parentFile, "$name.corrupt-$ts")
    runCatching { renameTo(target) }
}
