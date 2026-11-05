package dev.stanislav.focusdeck.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.io.File

/**
 * Выразительные шрифты берутся из самой системы Pixel, а не кладутся в APK.
 *
 * Google Sans на устройстве — переменный шрифт с кириллицей (wght 400–700), им набраны
 * заголовки. Google Sans Flex кириллицы не имеет и для русского интерфейса не подходит.
 * Roboto Flex даёт узкие плотные цифры — ими набраны проценты и счётчики, как на
 * циферблатах. На другом устройстве файла может не быть — тогда обычный системный шрифт.
 */
@OptIn(ExperimentalTextApi::class)
object FocusFonts {
    private const val GOOGLE_SANS = "/product/fonts/GoogleSans-Regular.ttf"
    private const val ROBOTO_FLEX = "/system/fonts/RobotoFlex-Regular.ttf"

    val display: FontFamily by lazy {
        variable(GOOGLE_SANS) { weight ->
            FontVariation.Settings(FontVariation.weight(weight.weight.coerceIn(400, 700)), FontVariation.Setting("opsz", 18f))
        }
    }

    val numeral: FontFamily by lazy {
        variable(ROBOTO_FLEX) { weight ->
            FontVariation.Settings(
                FontVariation.weight(weight.weight.coerceIn(100, 1000)),
                FontVariation.width(if (weight.weight >= 600) 84f else 100f),
                FontVariation.Setting("opsz", 72f),
            )
        }
    }

    private fun variable(path: String, settings: (FontWeight) -> FontVariation.Settings): FontFamily {
        val file = File(path)
        if (!runCatching { file.canRead() }.getOrDefault(false)) return FontFamily.Default
        return runCatching {
            FontFamily(listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold)
                .map { weight -> Font(file, weight, variationSettings = settings(weight)) })
        }.getOrDefault(FontFamily.Default)
    }
}
