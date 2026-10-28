package com.eunho.tagohub.ui.common

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * 한 줄 글씨: 자리가 모자라면 잘리거나 두 줄이 되는 대신 글자 크기를 조금씩 줄여 끝까지 다 보이게 한다.
 * 좁은 알약·버튼 이름("GTX-A", "여기서 출발")용. [minSizeSp] 보다 작아지진 않음.
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxSizeSp: Float = 14f,
    minSizeSp: Float = 9f,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
) {
    var size by remember(text, maxSizeSp) { mutableFloatStateOf(maxSizeSp) }
    Text(
        text,
        modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
        style = LocalTextStyle.current,
        onTextLayout = { if (it.didOverflowWidth && size > minSizeSp) size = (size - 1f).coerceAtLeast(minSizeSp) },
    )
}
