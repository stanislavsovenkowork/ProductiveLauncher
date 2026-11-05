package dev.stanislav.focusdeck.ui.life

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stanislav.focusdeck.data.life.LifeEntry
import dev.stanislav.focusdeck.data.life.LifeKind
import dev.stanislav.focusdeck.data.life.LifeStatus
import dev.stanislav.focusdeck.ui.theme.FocusFonts
import dev.stanislav.focusdeck.ui.theme.LocalFocusDeckColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Type scale of the life screens: display face for names, dense numerals for progress. */
internal object LifeType {
    val hero @Composable get() = TextStyle(fontFamily = FocusFonts.display, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp, color = MaterialTheme.colorScheme.onSurface)
    val title @Composable get() = TextStyle(fontFamily = FocusFonts.display, fontWeight = FontWeight.Medium,
        fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.1).sp, color = MaterialTheme.colorScheme.onSurface)
    val question @Composable get() = TextStyle(fontFamily = FocusFonts.display, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp, color = MaterialTheme.colorScheme.onSurface)
    val input @Composable get() = TextStyle(fontFamily = FocusFonts.display, fontWeight = FontWeight.Normal,
        fontSize = 20.sp, lineHeight = 28.sp, color = MaterialTheme.colorScheme.onSurface)
    val body @Composable get() = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    val muted @Composable get() = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val section @Composable get() = TextStyle(fontFamily = FocusFonts.display, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val bigNumber @Composable get() = TextStyle(fontFamily = FocusFonts.numeral, fontWeight = FontWeight.Bold,
        fontSize = 44.sp, lineHeight = 44.sp, letterSpacing = (-1).sp, fontFeatureSettings = "tnum")
    val number @Composable get() = TextStyle(fontFamily = FocusFonts.numeral, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum")
    /** A quoted reason reads as the person's own voice, not a form field. */
    val quote @Composable get() = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 17.sp, lineHeight = 25.sp,
        color = MaterialTheme.colorScheme.onSurface)
}

/** Each kind keeps one hue everywhere: badge, ring, heat cells, confetti. */
internal data class KindTone(val accent: Color, val container: Color, val onContainer: Color, val icon: ImageVector)

@Composable
internal fun kindTone(kind: LifeKind): KindTone {
    val scheme = MaterialTheme.colorScheme
    val deck = LocalFocusDeckColors.current
    return when (kind) {
        LifeKind.PROJECT -> KindTone(scheme.primary, scheme.primaryContainer, scheme.onPrimaryContainer, Icons.Rounded.RocketLaunch)
        LifeKind.GOAL -> KindTone(deck.attention, deck.attentionContainer, deck.onAttentionContainer, Icons.Rounded.TrackChanges)
        LifeKind.AREA -> KindTone(scheme.tertiary, scheme.tertiaryContainer, scheme.onTertiaryContainer, Icons.Rounded.Spa)
        LifeKind.DECISION -> KindTone(Color(0xFFCDBBF4), Color(0xFF3A2E57), Color(0xFFEADDFF), Icons.Rounded.AltRoute)
        LifeKind.REVIEW -> KindTone(scheme.secondary, scheme.secondaryContainer, scheme.onSecondaryContainer, Icons.Rounded.Insights)
    }
}

/** Tactile press: cards sink slightly under the finger. */
@Composable
internal fun Modifier.pressable(onClick: (() -> Unit)?, enabled: Boolean = true): Modifier {
    if (onClick == null) return this
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "press")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, onClick = onClick)
}

@Composable
internal fun LifeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    tint: Color? = null,
    padding: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    val base = MaterialTheme.colorScheme.surfaceContainer
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .fillMaxWidth()
            .pressable(onClick)
            .clip(shape)
            .background(if (tint != null) Brush.verticalGradient(listOf(tint.copy(alpha = 0.55f), base)) else Brush.linearGradient(listOf(base, base)))
            .padding(padding),
    ) { content() }
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(Locale.forLanguageTag("ru")), style = LifeType.section, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
internal fun KindBadge(entry: LifeEntry, modifier: Modifier = Modifier) {
    val tone = kindTone(entry.kind)
    val muted = entry.status in listOf(LifeStatus.PAUSED, LifeStatus.ARCHIVED, LifeStatus.DRAFT)
    Row(
        modifier.clip(CircleShape).background(tone.container.copy(alpha = if (muted) 0.45f else 0.9f))
            .padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(if (entry.status == LifeStatus.DONE && entry.kind == LifeKind.GOAL) Icons.Rounded.EmojiEvents else tone.icon,
            null, tint = tone.onContainer, modifier = Modifier.size(14.dp))
        Text("${kindShort(entry.kind)} · ${entry.statusLabel.lowercase()}", style = MaterialTheme.typography.labelMedium, color = tone.onContainer)
    }
}

/** Round progress with a springy sweep; the value is always a measured one. */
@Composable
internal fun ProgressRing(
    fraction: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    stroke: Dp = 7.dp,
    center: @Composable BoxScope.() -> Unit = {},
) {
    val sweep by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessVeryLow), label = "ring")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = stroke.toPx()
            val inset = width / 2
            val arcSize = Size(this.size.width - width, this.size.height - width)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(width))
            if (fraction != null && sweep > 0.001f) {
                drawArc(
                    Brush.sweepGradient(listOf(color.copy(alpha = 0.55f), color, color)),
                    -90f, 360f * sweep, false, Offset(inset, inset), arcSize, style = Stroke(width, cap = StrokeCap.Round),
                )
            }
        }
        center()
    }
}

@Composable
internal fun PercentLabel(fraction: Float?, color: Color, style: TextStyle = LifeType.number) {
    val value by animateIntAsState(((fraction ?: 0f) * 100).toInt(), tween(900, easing = FastOutSlowInEasing), label = "pct")
    if (fraction == null) Text("—", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else Text("$value%", style = style, color = color)
}

/** One segment per milestone: ticking one fills a visible piece, not an abstract percent. */
@Composable
internal fun SegmentedBar(done: Int, total: Int, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    if (total <= 0) return
    if (total > 16) {
        val fraction by animateFloatAsState(done.toFloat() / total, spring(stiffness = Spring.StiffnessLow), label = "bar")
        Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
            Box(Modifier.fillMaxWidth(fraction).height(height).clip(CircleShape).background(color))
        }
        return
    }
    Row(modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(total) { index ->
            val filled by animateFloatAsState(if (index < done) 1f else 0f,
                spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow), label = "seg")
            Box(Modifier.weight(1f).height(height).clip(CircleShape).background(track)) {
                Box(Modifier.fillMaxWidth(filled).height(height).clip(CircleShape).background(color))
            }
        }
    }
}

@Composable
internal fun SmoothBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val value by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessVeryLow), label = "smooth")
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Box(Modifier.fillMaxWidth(value).height(height).clip(CircleShape)
            .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.6f), color))))
    }
}

/**
 * Days with completed linked steps. Empty days are neutral cells, never red:
 * rest days are part of the plan, not a broken streak.
 */
@Composable
internal fun HeatStrip(
    doneByDay: Map<LocalDate, Int>,
    color: Color,
    days: Int,
    modifier: Modifier = Modifier,
    cell: Dp = 10.dp,
    today: LocalDate = LocalDate.now(),
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (back in days - 1 downTo 0) {
            val day = today.minusDays(back.toLong())
            HeatCell(doneByDay[day] ?: 0, color, cell, day == today)
        }
    }
}

/** Four weeks as rows of Monday…Sunday. */
@Composable
internal fun HeatGrid(doneByDay: Map<LocalDate, Int>, color: Color, modifier: Modifier = Modifier, cell: Dp = 14.dp, today: LocalDate = LocalDate.now()) {
    val firstMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(3)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (week in 0 until 4) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (day in 0 until 7) {
                val date = firstMonday.plusDays(week * 7L + day)
                if (date.isAfter(today)) Box(Modifier.size(cell).border(1.dp, MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(4.dp)))
                else HeatCell(doneByDay[date] ?: 0, color, cell, date == today)
            }
        }
    }
}

@Composable
private fun HeatCell(count: Int, color: Color, size: Dp, today: Boolean) {
    val target = when {
        count <= 0 -> MaterialTheme.colorScheme.surfaceContainerHighest
        count == 1 -> color.copy(alpha = 0.38f)
        count == 2 -> color.copy(alpha = 0.66f)
        else -> color
    }
    val fill by animateColorAsState(target, tween(500), label = "heat")
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 3.5f)).background(fill)
            .then(if (today) Modifier.border(1.dp, color.copy(alpha = 0.9f), RoundedCornerShape(size / 3.5f)) else Modifier),
    )
}

/** Round checkbox with a bounce; the tick itself is the reward. */
@Composable
internal fun CheckOrb(checked: Boolean, color: Color, onToggle: (() -> Unit)?, modifier: Modifier = Modifier, size: Dp = 26.dp) {
    val haptics = LocalHapticFeedback.current
    val fill by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "orb")
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier.size(size).clip(CircleShape)
            .then(if (onToggle != null) Modifier.clickable {
                if (!checked) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggle()
            } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            drawCircle(outline.copy(alpha = 1f - fill), style = Stroke(2.dp.toPx()))
            drawCircle(color, radius = this.size.minDimension / 2 * fill.coerceIn(0f, 1.2f))
        }
        if (fill > 0.5f) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(size * 0.62f).scale(fill.coerceIn(0f, 1.15f)))
    }
}

@Composable
internal fun AnimatedCount(value: Int, style: TextStyle, color: Color = MaterialTheme.colorScheme.onSurface) {
    val shown by animateIntAsState(value, tween(700, easing = FastOutSlowInEasing), label = "count")
    Text(shown.toString(), style = style, color = color)
}

/** Deadline as distance, the way people think about it. Overdue is amber, not red. */
@Composable
internal fun DueChip(date: LocalDate, modifier: Modifier = Modifier, today: LocalDate = LocalDate.now()) {
    val days = ChronoUnit.DAYS.between(today, date)
    val deck = LocalFocusDeckColors.current
    val (text, overdue) = when {
        days < 0 -> "срок прошёл ${-days} дн назад" to true
        days == 0L -> "срок сегодня" to true
        days == 1L -> "срок завтра" to false
        days < 60 -> "ещё $days дн" to false
        days < 365 -> "ещё ${days / 30} мес" to false
        else -> "ещё ${"%.1f".format(Locale.forLanguageTag("ru"), days / 365.0).removeSuffix(",0")} г" to false
    }
    Text(text, style = MaterialTheme.typography.labelMedium,
        color = if (overdue) deck.onAttentionContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.clip(CircleShape)
            .background(if (overdue) deck.attentionContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 4.dp))
}

/** Compact pill action — for secondary commands inside cards. */
@Composable
internal fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    Row(
        modifier.height(36.dp).clip(CircleShape).background(container.copy(alpha = if (enabled) container.alpha else container.alpha * 0.4f))
            .pressable(onClick, enabled).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, null, tint = content, modifier = Modifier.size(16.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = content.copy(alpha = if (enabled) 1f else 0.5f), maxLines = 1)
    }
}

/** Selectable chip for wizard answers and quick verdicts. */
@Composable
internal fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, accent: Color = MaterialTheme.colorScheme.primary, icon: ImageVector? = null) {
    val bg by animateColorAsState(if (selected) accent else MaterialTheme.colorScheme.surfaceContainerHigh, tween(220), label = "chip")
    val fg by animateColorAsState(if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface, tween(220), label = "chipText")
    Row(
        Modifier.height(40.dp).clip(CircleShape).background(bg).pressable(onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(16.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private data class Particle(val angle: Float, val speed: Float, val spin: Float, val color: Color, val w: Float, val h: Float, val delay: Float)

/**
 * Finishing something real deserves a moment. Confetti only for finished projects/goals
 * and a full set of milestones; a single tick gets a small toast.
 */
@Composable
internal fun CelebrationLayer(celebration: Celebration?, onDone: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val progress = remember { Animatable(0f) }
    val palette = listOf(MaterialTheme.colorScheme.primary, LocalFocusDeckColors.current.attention,
        MaterialTheme.colorScheme.tertiary, Color(0xFFCDBBF4), Color(0xFFFFB4A8))
    val particles = remember(celebration?.id) {
        val random = Random(celebration?.id ?: 0L)
        List(if (celebration?.big == true) 90 else 26) {
            Particle(
                angle = (-90f + random.nextFloat() * 150f - 75f) * (PI.toFloat() / 180f),
                speed = 0.55f + random.nextFloat() * 0.75f,
                spin = random.nextFloat() * 720f - 360f,
                color = palette[random.nextInt(palette.size)],
                w = 6f + random.nextFloat() * 6f, h = 10f + random.nextFloat() * 8f,
                delay = random.nextFloat() * 0.15f,
            )
        }
    }
    LaunchedEffect(celebration?.id) {
        if (celebration == null) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        progress.snapTo(0f)
        progress.animateTo(1f, tween(if (celebration.big) 2600 else 1500, easing = LinearEasing))
        onDone()
    }
    if (celebration == null) return
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val t = progress.value
            val origin = Offset(size.width / 2, size.height * if (celebration.big) 0.62f else 0.82f)
            val reach = size.height * if (celebration.big) 0.9f else 0.35f
            particles.forEach { p ->
                val local = ((t - p.delay) / (1f - p.delay)).coerceIn(0f, 1f)
                if (local <= 0f) return@forEach
                val x = origin.x + cos(p.angle) * p.speed * reach * local
                val y = origin.y + sin(p.angle) * p.speed * reach * local + reach * 0.9f * local * local
                val alpha = (1f - local).coerceIn(0f, 1f)
                rotate(p.spin * local, Offset(x, y)) {
                    drawRoundRect(p.color.copy(alpha = alpha), Offset(x - p.w / 2, y - p.h / 2), Size(p.w, p.h),
                        androidx.compose.ui.geometry.CornerRadius(2f, 2f))
                }
            }
        }
        AnimatedVisibility(
            visible = progress.value < 0.85f,
            enter = scaleIn(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.6f) + fadeIn(),
            exit = scaleOut(targetScale = 0.9f) + fadeOut(),
            modifier = Modifier.align(if (celebration.big) Alignment.Center else Alignment.BottomCenter)
                .padding(bottom = if (celebration.big) 0.dp else 96.dp),
        ) {
            Column(
                Modifier.clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 24.dp, vertical = if (celebration.big) 22.dp else 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(if (celebration.big) Icons.Rounded.EmojiEvents else Icons.Rounded.AutoAwesome, null,
                    tint = LocalFocusDeckColors.current.attention, modifier = Modifier.size(if (celebration.big) 40.dp else 22.dp))
                Text(celebration.title, style = if (celebration.big) LifeType.hero.copy(fontSize = 22.sp) else LifeType.title)
                if (celebration.subtitle.isNotBlank()) Text(celebration.subtitle, style = LifeType.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(240.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

internal fun kindShort(kind: LifeKind): String = when (kind) {
    LifeKind.AREA -> "Сфера"
    LifeKind.GOAL -> "Цель"
    LifeKind.PROJECT -> "Проект"
    LifeKind.DECISION -> "Решение"
    LifeKind.REVIEW -> "Обзор"
}

internal fun relativeDay(date: LocalDate, today: LocalDate = LocalDate.now()): String {
    val ru = Locale.forLanguageTag("ru")
    return when (ChronoUnit.DAYS.between(date, today)) {
        0L -> "сегодня"
        1L -> "вчера"
        -1L -> "завтра"
        in 2L..6L -> date.format(DateTimeFormatter.ofPattern("EEEE", ru))
        else -> date.format(DateTimeFormatter.ofPattern("d MMM", ru))
    }
}

@Composable
internal fun Gap(height: Dp) = Spacer(Modifier.height(height))
