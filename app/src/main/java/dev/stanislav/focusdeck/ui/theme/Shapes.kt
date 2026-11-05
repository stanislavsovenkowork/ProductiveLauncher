package dev.stanislav.focusdeck.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Скругления из спецификации макета. */
val FocusDeckShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Именованные радиусы для мест, где нужен конкретный, а не роль из Shapes. */
object FocusRadius {
    val card = RoundedCornerShape(28.dp)
    val block = RoundedCornerShape(20.dp)
    val field = RoundedCornerShape(16.dp)
    val chip = RoundedCornerShape(8.dp)
    val pill = RoundedCornerShape(50)
    val tape = RoundedCornerShape(6.dp)

    /** Скруглён только правый край — для блока, обрезанного слева краем окна. */
    val chipEnd = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 8.dp, bottomEnd = 8.dp)
}
