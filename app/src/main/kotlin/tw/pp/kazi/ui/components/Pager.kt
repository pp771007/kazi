package tw.pp.kazi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tw.pp.kazi.ui.WindowSize
import tw.pp.kazi.ui.enterFocusOn
import tw.pp.kazi.ui.isCompact
import tw.pp.kazi.ui.isTv
import tw.pp.kazi.ui.pagePadding
import tw.pp.kazi.ui.theme.AppColors
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private const val JUMP_INPUT_MAX_DIGITS = 4
private val JUMP_INPUT_WIDTH = 64.dp

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun Pager(
    page: Int,
    pageCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    windowSize: WindowSize,
    onJump: (Int) -> Unit = {},
    // simplified: 不顯示「頁碼輸入 + 跳轉」，只剩上下頁。給「客戶端切片翻頁」這種頁數通常 < 10 的場景用
    simplified: Boolean = false,
    // accent: 整顆 Pager 包淡 primary 底色 + 圓框，跟旁邊的「外層 API 翻頁」視覺上分開
    accent: Boolean = false,
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    val compact = windowSize.isCompact
    val tv = windowSize.isTv
    // 進入這組分頁時預設 focus 在「下一頁」(還有下一頁時)。靠 focusGroup + focusRestorer,
    // 不靠外部 grid 卡片手動指落點(那種跨元件落點正是 FocusRequester 沒掛上時的閃退來源)。
    // Pager 是 Row 不是 LazyRow，按鈕不會被虛擬化 → 有下一頁時 nextFocus 必定掛著、安全。
    val nextFocus = remember { FocusRequester() }
    // page 變動時把 input 清掉，避免使用者輸入完按下「跳轉」之後欄位殘留舊值
    var jumpInput by remember(page) { mutableStateOf("") }
    val parsedJump = jumpInput.toIntOrNull()
    val canJump = parsedJump != null && parsedJump in 1..pageCount && parsedJump != page

    fun submitJump() {
        if (canJump) parsedJump?.let(onJump)
    }

    val outer = if (accent) {
        modifier
            .fillMaxWidth()
            .padding(horizontal = windowSize.pagePadding(), vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.Primary.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    } else if (tv) {
        // TV 上 LazyVerticalGrid 自己的 contentPadding 已經提供 pagePadding，這裡只補垂直 padding
        modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    } else {
        modifier
            .fillMaxWidth()
            .padding(horizontal = windowSize.pagePadding(), vertical = 12.dp)
    }

    // 進到這組分頁時固定停在「下一頁」(還有下一頁時)。onFocusChanged+requestFocus 是真 focus 可按,
    // 且只在進入瞬間導向 → 之後按←到上一頁仍可用。
    Row(
        modifier = outer.enterFocusOn(nextFocus, enabled = page < pageCount),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label != null) {
            Text(
                label,
                color = AppColors.OnBgMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
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
            modifier = Modifier.focusRequester(nextFocus),
        )
        if (!simplified) {
            Spacer(Modifier.weight(1f))
            JumpInput(
                value = jumpInput,
                onValueChange = { jumpInput = it.filter { c -> c.isDigit() }.take(JUMP_INPUT_MAX_DIGITS) },
                onSubmit = ::submitJump,
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
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun JumpInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
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
                if (value.isEmpty()) {
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
}
