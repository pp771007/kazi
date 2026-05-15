package tw.pp.kazi.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tw.pp.kazi.ui.WindowSize
import tw.pp.kazi.ui.isCompact
import tw.pp.kazi.ui.isTv
import tw.pp.kazi.ui.pagePadding
import tw.pp.kazi.ui.theme.AppColors
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private const val JUMP_INPUT_MAX_DIGITS = 4
private val JUMP_INPUT_WIDTH = 64.dp

// TV 雙列布局：「上一頁」按鈕的最小寬度，比文字本身寬，這樣 D-pad 從卡片往下進來
// 第一個落點是這顆大按鈕，視覺上也跟整列等高的「下一頁」呼應
private val TV_PREV_BUTTON_MIN_WIDTH = 200.dp

@OptIn(
    ExperimentalTvMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalComposeUiApi::class,
)
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
    // page 變動時把 input 清掉，避免使用者輸入完按下「跳轉」之後欄位殘留舊值
    var jumpInput by remember(page) { mutableStateOf("") }
    val parsedJump = jumpInput.toIntOrNull()
    val canJump = parsedJump != null && parsedJump in 1..pageCount && parsedJump != page
    // TV 雙列布局時讓 grid 卡片按↓固定落到「下一頁」。
    // 不靠 spatial focus 自己判斷 — fillMaxWidth 的「下一頁」中心點在水平中間，從左/右邊卡片
    // 往下時水平偏差會輸給第二列窄按鈕（上一頁／頁碼／跳轉），導致實際 focus 跑去那些。
    // 用 focusGroup + focusProperties.enter 把整個 Pager 包成單一 entry，外部任何方向進來
    // 都先 redirect 到下一頁。
    val nextPageRequester = remember { FocusRequester() }

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
        // TV 模式 Pager 是 LazyVerticalGrid footer，grid 自己的 contentPadding 已經提供 pagePadding，
        // 這裡再加會讓「下一頁」按鈕左緣縮在最左卡片內側 → ↓ spatial focus 從最左卡片下來會被
        // 中心更貼近的「上一頁」（短按鈕）吃掉。拿掉 horizontal padding 後下一頁橫跨整列寬度。
        modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    } else {
        modifier
            .fillMaxWidth()
            .padding(horizontal = windowSize.pagePadding(), vertical = 12.dp)
    }

    if (tv) {
        // TV 雙列布局：
        //   第一列：「下一頁」獨佔一列、撐滿寬度
        //   第二列：「上一頁」（加長）+ 頁碼 + （非 simplified）輸入框 + 跳轉
        // 用 focusGroup + enter redirect 強制把外部進來的 focus 帶到「下一頁」（見 nextPageRequester 註解）。
        // 已在最後一頁時下一頁 disabled、不可 focus，fallback Default 讓 spatial 自己挑（會落到「上一頁」）。
        Column(
            modifier = outer
                .focusGroup()
                .focusProperties {
                    enter = {
                        if (page < pageCount) nextPageRequester else FocusRequester.Default
                    }
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppButton(
                text = "下一頁",
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                onClick = onNext,
                enabled = page < pageCount,
                primary = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(nextPageRequester),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    modifier = Modifier.widthIn(min = TV_PREV_BUTTON_MIN_WIDTH),
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
                    )
                }
            }
        }
        return
    }

    Row(
        modifier = outer,
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
