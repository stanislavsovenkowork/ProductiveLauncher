package dev.stanislav.focusdeck.data.repo

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Запросы к системным провайдерам идут в IO, а не в том потоке, откуда их позвали.
 * ContentResolver и UsageStatsManager отвечают десятки и сотни миллисекунд, а звали
 * их из viewModelScope, то есть с главного потока — интерфейс на это время замирал.
 */
class UsageStatsRepository(private val context: Context) {

    @Suppress("DEPRECATION")
    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Суммарное экранное время с начала текущих суток, в миллисекундах. */
    suspend fun screenTimeTodayMillis(): Long = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext 0L
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val startOfDay = ZonedDateTime.now(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
        return@withContext stats?.sumOf { it.totalTimeInForeground } ?: 0L
    }

    /**
     * Экранное время за конкретные сутки. Нужно для итога за вчера: его пишут
     * уже сегодня, когда «с начала суток» считает совсем другой день.
     *
     * Система хранит статистику ограниченное время, и за давние дни вернётся ноль —
     * поэтому итог дописывается на следующий же день, а не копится про запас.
     */
    suspend fun screenTimeMillisOn(date: LocalDate): Long = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext 0L
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val zone = ZoneId.systemDefault()
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, to)
        return@withContext stats?.sumOf { it.totalTimeInForeground } ?: 0L
    }

    /** Минуты использования сегодня по каждому пакету. */
    suspend fun perAppMinutesToday(): Map<String, Long> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyMap()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val startOfDay = ZonedDateTime.now(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
        return@withContext stats
            ?.associate { it.packageName to it.totalTimeInForeground / 60_000 }
            ?.filterValues { it > 0 }
            ?: emptyMap()
    }
}
