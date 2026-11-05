package dev.stanislav.focusdeck.ui.theme

import androidx.compose.ui.text.TextStyle

/**
 * Табличные цифры: одинаковая ширина знаков, чтобы таймер и счётчики
 * не дёргались при смене разряда (см. спеку макета).
 */
fun TextStyle.tabularNums(): TextStyle = copy(fontFeatureSettings = "tnum")
