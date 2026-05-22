package tw.pp.kazi.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import tw.pp.kazi.data.PosterConfig
import tw.pp.kazi.data.ViewMode

/** 由單張圖的長寬比 (width / height) 判斷它屬於哪種方向。 */
private fun classify(ratio: Float): ViewMode = when {
    ratio >= PosterConfig.LANDSCAPE_RATIO -> ViewMode.Landscape
    ratio <= PosterConfig.PORTRAIT_RATIO -> ViewMode.Portrait
    else -> ViewMode.Square
}

/**
 * 抽前幾張預覽圖實際解碼出長寬，多數決出整批圖的方向。MacCMS 的 API 只給圖片網址、不給尺寸，
 * 所以方向只能靠實際載入後量。解碼時縮到 [PosterConfig.DETECT_DECODE_PX] 邊長即可（只要比例不要原圖），
 * 量完的圖也順便進了 Coil 快取，等下真的要顯示時是現成的。
 */
suspend fun detectViewMode(
    context: Context,
    imageUrls: List<String>,
    default: ViewMode,
): ViewMode = withContext(Dispatchers.IO) {
    val sample = imageUrls.filter { it.isNotBlank() }.take(PosterConfig.DETECT_SAMPLE_COUNT)
    if (sample.isEmpty()) return@withContext default

    val ratios = coroutineScope {
        sample.map { url ->
            async {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(PosterConfig.DETECT_DECODE_PX, PosterConfig.DETECT_DECODE_PX)
                    .build()
                val drawable = (context.imageLoader.execute(request) as? SuccessResult)?.drawable
                val w = drawable?.intrinsicWidth ?: 0
                val h = drawable?.intrinsicHeight ?: 0
                if (w > 0 && h > 0) w.toFloat() / h else null
            }
        }.awaitAll().filterNotNull()
    }
    if (ratios.isEmpty()) return@withContext default

    ratios.map(::classify)
        .groupingBy { it }.eachCount()
        .maxByOrNull { it.value }!!.key
}

/**
 * 給混站畫面（搜尋 / 收藏）用：偵測完成前先用 [default] 撐著，量完再切到多數決結果。
 * [imageUrls] 內容變了（換一批結果）才重新偵測。
 */
@Composable
fun rememberAutoViewMode(imageUrls: List<String>, default: ViewMode = ViewMode.Default): ViewMode {
    val context = LocalContext.current
    val sample = remember(imageUrls) {
        imageUrls.filter { it.isNotBlank() }.take(PosterConfig.DETECT_SAMPLE_COUNT)
    }
    val mode by produceState(default, sample) {
        if (sample.isNotEmpty()) value = detectViewMode(context, sample, default)
    }
    return mode
}
