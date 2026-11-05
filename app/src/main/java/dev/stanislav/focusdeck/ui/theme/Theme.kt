package dev.stanislav.focusdeck.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Палитра из спецификации макета, посаженная на чистый чёрный фон.
 *
 * Отступление от брифа (шаг 10): цвета не берутся из dynamicDarkColorScheme.
 * Причина — запрошен именно чёрный фон и предсказуемый баланс поверхностей;
 * динамическая схема даёт свой surface от обоев и чёрный фон в неё не ложится.
 */
private val FocusDeckDarkScheme = darkColorScheme(
    // Акценты — мятная ветка
    primary = Color(0xFFA5D6B8),
    onPrimary = Color(0xFF0A3A22),
    primaryContainer = Color(0xFF26523A),
    onPrimaryContainer = Color(0xFFC1F2D3),

    // Шалфейная ветка — задачи и привычки
    secondary = Color(0xFFBAC9BE),
    onSecondary = Color(0xFF25382C),
    secondaryContainer = Color(0xFF384B3F),
    onSecondaryContainer = Color(0xFFD2E8D9),

    tertiary = Color(0xFFA9CCD6),
    onTertiary = Color(0xFF0B363F),
    tertiaryContainer = Color(0xFF244C55),
    onTertiaryContainer = Color(0xFFC4E9F3),

    // Фон — чистый чёрный
    background = Color(0xFF000000),
    onBackground = Color(0xFFE0E4DD),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE0E4DD),

    // Поверхности: подняты от чёрного с сохранением зелёного подтона макета
    surfaceContainerLowest = Color(0xFF050705),
    surfaceContainerLow = Color(0xFF0D110E),
    surfaceContainer = Color(0xFF171D18),
    surfaceContainerHigh = Color(0xFF212820),
    surfaceContainerHighest = Color(0xFF2A322B),

    surfaceVariant = Color(0xFF171D18),
    onSurfaceVariant = Color(0xFFC0C9BF),

    outline = Color(0xFF8A938A),
    outlineVariant = Color(0xFF404942),

    // error оставлен штатным — по спеке зарезервирован под жёсткий сигнал
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    scrim = Color(0xFF000000),
)

@Composable
fun FocusDeckTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = FocusDeckDarkScheme,
        typography = FocusDeckTypography,
        shapes = FocusDeckShapes,
        content = content,
    )
}
