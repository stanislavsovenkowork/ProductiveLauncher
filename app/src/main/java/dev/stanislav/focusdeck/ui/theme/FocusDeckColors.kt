package dev.stanislav.focusdeck.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Янтарная роль "attention" — семантически отдельная от Material3 error,
 * см. спеку макета: error зарезервирован под по-настоящему жёсткий сигнал.
 */
data class FocusDeckColors(
    val attention: Color,
    val attentionContainer: Color,
    val onAttentionContainer: Color,
    /**
     * Опорные точки градиента блоков — в градусах цветового круга, от утра к ночи.
     *
     * Хранятся именно оттенки, а не готовые цвета. Раньше палитра была набором
     * пастельных цветов, а заливка получалась затемнением пастели до чёрного —
     * то есть низкая насыщенность вместе с низкой яркостью. Так получается грязь:
     * цвета в ней уже нет, а серого ещё мало. Держа оттенок отдельно, насыщенность
     * для тёмной заливки можно поднять, а не уронить вместе с яркостью.
     *
     * Ряд намеренно монотонный, без перехода через 0°: линейная интерполяция
     * между 330° и 30° пошла бы назад через весь круг.
     */
    val taskHues: List<Float>,
)

/**
 * Ход дня: мятная ветка темы уходит в её же янтарную — та, что в метриках
 * и в шкале экранного времени.
 *
 * Опорные оттенки расставлены неравномерно: до девяти утра это глубокая петроль,
 * дальше день до шестнадцати часов держится в зелени, около половины шестого
 * проходит через жёлтый и к восьми вечера приходит в оранжевый. Так сделано не ради красоты. Путь из зелёного в тёплое
 * неизбежно проходит через 80–100 градусов, а это хаки — зона, где цвет читается
 * как грязь. Размазать её по дню значит выкрасить в неё половину задач, поэтому
 * она сжата примерно до часа и проходится на подъёме светлоты, где тот же
 * оттенок читается лаймом, а не болотом.
 *
 * Насыщенность к вечеру растёт, а не падает: у янтарной ветки темы контейнер
 * #4A3A18 заметно насыщеннее зелёного #26523A, и на тёплом конце запас нужен
 * больше — иначе тёмное золото скатывается в бурый.
 */
private const val ACCENT_SATURATION_MORNING = 0.24f
private const val ACCENT_SATURATION_EVENING = 0.52f
private const val ACCENT_VALUE_MORNING = 0.82f
private const val ACCENT_VALUE_EVENING = 0.90f

/**
 * Заливка плотная, а не еле отличимая от фона.
 *
 * Тёмный тон на чёрном читался как полупрозрачная плёнка, и контраст между
 * соседними блоками держался на обводке. Обводка убрана, поэтому разницу
 * должен нести сам цвет: светлота идёт от 0.24 к 0.46, а не от 0.15 к 0.30.
 *
 * Насыщенность растёт к вечеру, с отдельным подъёмом посередине.
 *
 * Посередине ряда оттенок проходит жёлто-зелёную зону, где низкая хрома читается
 * как хаки, а высокая — как лайм; там подъём нужен, чтобы не получить грязь.
 * На тёплом конце она поднята ещё выше: у янтарной ветки темы контейнер #4A3A18
 * сам по себе насыщеннее зелёного #26523A, и без запаса тёмное золото выходило
 * блёклым, а не золотым.
 */
private val FILL_SATURATION = floatArrayOf(
    0.58f, 0.54f, 0.52f, 0.54f, 0.58f, 0.62f, 0.68f, 0.76f, 0.82f, 0.82f, 0.80f, 0.78f, 0.76f,
)

/**
 * Светлота растёт до жёлтого и после него немного спадает.
 *
 * Начало шкалы опущено ниже остального ряда: рассветные часы должны быть
 * глубокими. Насыщенность там, наоборот, поднята — на такой темноте без запаса
 * хромы сине-зелёный выцветает в серый.
 *
 * Ровно растущей её держать нельзя. Жёлтый по природе светлый: тёмный жёлтый —
 * это оливка, и на ровной шкале переход зелёный → оранжевый проскакивал жёлтый
 * незаметно, через хаки. Подъём на жёлтом делает его собственно жёлтым, а
 * оранжевому и красному светлота уже не нужна — они читаются и в глубине.
 */
private val FILL_VALUE = floatArrayOf(
    0.18f, 0.21f, 0.25f, 0.30f, 0.33f, 0.36f, 0.40f, 0.46f, 0.52f, 0.50f, 0.48f, 0.46f, 0.44f,
)

/** Насколько светлеет заливка у взятого или выделенного блока. */
private const val FILL_EMPHASIS = 0.08f

private fun lerpF(from: Float, to: Float, t: Float): Float = from + (to - from) * t

/** Значение из ряда опорных точек, равномерно разложенных по [0,1]. */
private fun rampAt(points: FloatArray, fraction: Float): Float {
    if (points.isEmpty()) return 0f
    if (points.size == 1) return points[0]
    val position = fraction.coerceIn(0f, 1f) * (points.size - 1)
    val index = position.toInt().coerceIn(0, points.size - 2)
    return points[index] + (points[index + 1] - points[index]) * (position - index)
}

/**
 * Оттенок по положению в дне: [fraction] — 0 в начале рабочего окна, 1 в конце.
 * При одном опорном оттенке возвращает его же — цвет задаёт светлота.
 */
fun FocusDeckColors.taskHue(fraction: Float): Float {
    if (taskHues.isEmpty()) return 0f
    if (taskHues.size == 1) return taskHues.first()
    val position = fraction.coerceIn(0f, 1f) * (taskHues.size - 1)
    val index = position.toInt().coerceIn(0, taskHues.size - 2)
    return taskHues[index] + (taskHues[index + 1] - taskHues[index]) * (position - index)
}

/** Контур, ручки и полоска на ленте дня. */
fun FocusDeckColors.taskAccent(fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    return Color.hsv(
        taskHue(t),
        lerpF(ACCENT_SATURATION_MORNING, ACCENT_SATURATION_EVENING, t),
        lerpF(ACCENT_VALUE_MORNING, ACCENT_VALUE_EVENING, t),
    )
}

/**
 * Сплошная заливка блока. Насыщенность держится выше, чем у контура: при низкой
 * светлоте цвет теряется быстрее, и без запаса тёмный блок скатывается в серый.
 */
fun FocusDeckColors.taskFill(fraction: Float, emphasised: Boolean): Color {
    val t = fraction.coerceIn(0f, 1f)
    val value = rampAt(FILL_VALUE, t) + if (emphasised) FILL_EMPHASIS else 0f
    return Color.hsv(taskHue(t), rampAt(FILL_SATURATION, t), value.coerceAtMost(1f))
}

private val DarkFocusDeckColors = FocusDeckColors(
    attention = Color(0xFFE9C46A),
    attentionContainer = Color(0xFF4A3A18),
    onAttentionContainer = Color(0xFFFBE3AE),
    // Полный день: глубокая петроль на рассвете -> зелень -> жёлтый -> оранжевый.
    // 185 -> 14 градусов. Раннее утро уведено в сине-зелёное и притемнено:
    // до девяти день ещё не начался, и цвет не должен звучать в полный голос.
    // Жёлтый выведен отдельной ступенью около половины шестого вечера, иначе
    // переход шёл сразу из зелёного в оранжевый и читался как провал через хаки.
    taskHues = listOf(
        185f, 170f, 156f, 147f, 143f, 138f, 128f, 75f, 48f, 33f, 24f, 18f, 14f,
    ),
)

val LocalFocusDeckColors = staticCompositionLocalOf { DarkFocusDeckColors }

/**
 * Та же палитра вне композиции: лента дня собирается во ViewModel,
 * а цвет задачи должен совпадать с её блоком на таймлайне.
 */
val launcherColors: FocusDeckColors = DarkFocusDeckColors
