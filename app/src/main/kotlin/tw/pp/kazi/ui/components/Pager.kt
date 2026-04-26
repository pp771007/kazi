package tw.pp.kazi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tw.pp.kazi.ui.WindowSize
import tw.pp.kazi.ui.isCompact
import tw.pp.kazi.ui.pagePadding
import tw.pp.kazi.ui.theme.AppColors
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private const val JUMP_INPUT_MAX_DIGITS = 4
private val JUMP_INPUT_WIDTH = 64.dp

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun Pager(
    page: Int,
    pageCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onJump: (Int) -> Unit,
    windowSize: WindowSize,
    modifier: Modifier = Modifier,
) {
    val compact = windowSize.isCompact
    // page 變動時把 input 清掉，避免使用者輸入完按下「跳轉」之後欄位殘留舊值
    var jumpInput by remember(page) { mutableStateOf("") }
    val parsedJump = jumpInput.toIntOrNull()
    val canJump = parsedJump != null && parsedJump in 1..pageCount && parsedJump != page

    fun submitJump() {
        if (canJump) parsedJump?.let(onJump)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = windowSize.pagePadding(), vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppButton(
            text = "上一頁",
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            onClick = onPrev,
            enabled = page > 1,
            primary = false,
            iconOnly = compact,
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0x22FFFFFF))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                "$page / $pageCount",
                color = AppColors.OnBg,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        AppButton(
            text = "下一頁",
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            onClick = onNext,
            enabled = page < pageCount,
            primary = false,
            iconOnly = compact,
        )
        Spacer(Modifier.weight(1f))
        BasicTextField(
            value = jumpInput,
            onValueChange = { jumpInput = it.filter { c -> c.isDigit() }.take(JUMP_INPUT_MAX_DIGITS) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submitJump() }),
            textStyle = TextStyle(
                color = AppColors.OnBg,
                fontSize = MaterialTheme.typography.labelMedium.fontSize,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = SolidColor(AppColors.Primary),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .width(JUMP_INPUT_WIDTH)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppColors.BgElevated)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (jumpInput.isEmpty()) {
                        Text(
                            "頁碼",
                            color = AppColors.OnBgDim,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.width(JUMP_INPUT_WIDTH),
        )
        AppButton(
            text = "跳轉",
            icon = Icons.Filled.KeyboardDoubleArrowRight,
            onClick = ::submitJump,
            enabled = canJump,
            primary = false,
            iconOnly = compact,
        )
    }
}
