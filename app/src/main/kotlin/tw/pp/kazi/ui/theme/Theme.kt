package tw.pp.kazi.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object AppColors {
    val Bg = Color(0xFF0A0A0F)
    val BgElevated = Color(0xFF161622)
    val BgCard = Color(0xFF1E1E2E)
    val Surface = Color(0xFF242436)
    val Border = Color(0x33FFFFFF)
    val Primary = Color(0xFF3B82F6)
    val PrimaryVariant = Color(0xFF60A5FA)
    val Secondary = Color(0xFF8B5CF6)
    val Accent = Color(0xFFEC4899)
    val Success = Color(0xFF10B981)
    val Warning = Color(0xFFF59E0B)
    val Error = Color(0xFFEF4444)
    val OnBg = Color(0xFFF1F5F9)
    val OnBgMuted = Color(0xFF94A3B8)
    val OnBgDim = Color(0xFF64748B)
    // 改白色：原本是 #3B82F6 跟 Primary / FocusableTag selected bg 同色，selected
    // tag 拿到 focus 時 border 直接撞色看不見。白色在所有底色（深底 / Primary 藍 /
    // BgCard）上都有強對比，TV 從遠處也最明顯（Netflix / YouTube 都用白）
    val FocusRing = Color(0xFFFFFFFF)
    val ShimmerStart = Color(0xFF1E1E2E)
    val ShimmerEnd = Color(0xFF2A2A3E)
}

val LocalAppColors = compositionLocalOf { AppColors }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KaziTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = AppColors.Primary,
        onPrimary = AppColors.OnBg,
        secondary = AppColors.Secondary,
        background = AppColors.Bg,
        onBackground = AppColors.OnBg,
        surface = AppColors.BgCard,
        onSurface = AppColors.OnBg,
        surfaceVariant = AppColors.Surface,
        onSurfaceVariant = AppColors.OnBgMuted,
        error = AppColors.Error,
    )
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
