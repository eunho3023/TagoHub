package com.eunho.tagohub.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.eunho.tagohub.R

/**
 * 거리·속도·시각처럼 자리가 흔들리면 안 되는 숫자용 글꼴: JetBrains Mono (SIL Open Font License, docs/fonts-license-JetBrainsMono-OFL.txt).
 * 글자는 기기 기본(노토 산스) 글꼴을 쓴다.
 */
val NumberFont = FontFamily(
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_medium, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    Font(R.font.jetbrains_mono_bold, FontWeight.SemiBold),
)
