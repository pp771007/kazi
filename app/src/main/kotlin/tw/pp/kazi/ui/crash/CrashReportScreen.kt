package tw.pp.kazi.ui.crash

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import tw.pp.kazi.ui.theme.AppColors

/**
 * 「上次崩潰」畫面。刻意做成自給自足：它在 KaziApp 提供 NavController / AppContainer 等
 * CompositionLocal 之前就渲染（要趕在會崩的正常畫面之前攔下來），所以不能用任何 Local* 或
 * 需要那些 Local 的共用元件（ScreenScaffold / AppButton）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CrashReportScreen(report: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val closeFocus = remember { FocusRequester() }

    // 進畫面把 focus 給「關閉」鈕，電視盒遙控器一進來就有落點
    LaunchedEffect(Unit) {
        runCatching { closeFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Bg)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "⚠ 上次發生崩潰",
            color = AppColors.Error,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
        )
        Text(
            text = "把這頁拍照或按「複製」傳給開發者，再按「關閉」就能繼續使用。",
            color = AppColors.OnBgMuted,
            fontSize = 14.sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(
                text = "關閉",
                primary = true,
                modifier = Modifier.focusRequester(closeFocus),
                onClick = onDismiss,
            )
            PillButton(
                text = "複製",
                primary = false,
                onClick = {
                    clipboard.setText(AnnotatedString(report))
                    Toast.makeText(context, "已複製崩潰報告到剪貼簿", Toast.LENGTH_SHORT).show()
                },
            )
        }

        // 報告本文：可捲動，並且 focusable 讓遙控器能往下捲
        val scroll = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(AppColors.BgCard)
                .focusable()
                .verticalScroll(scroll)
                .padding(14.dp),
        ) {
            Text(
                text = report,
                color = AppColors.OnBg,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun PillButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        primary -> AppColors.Primary
        else -> AppColors.BgCard
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(
                width = 2.dp,
                color = if (focused) AppColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (primary) Color.White else AppColors.OnBg,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
    }
}
