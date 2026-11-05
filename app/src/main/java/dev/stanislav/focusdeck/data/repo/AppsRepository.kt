package dev.stanislav.focusdeck.data.repo

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data /**
 * Запросы к системным провайдерам идут в IO, а не в том потоке, откуда их позвали.
 * ContentResolver и UsageStatsManager отвечают десятки и сотни миллисекунд, а звали
 * их из viewModelScope, то есть с главного потока — интерфейс на это время замирал.
 */
class InstalledApp(
    val packageName: String,
    val label: String,
    val minutesToday: Long,
)

class AppsRepository(
    private val context: Context,
    private val usageStatsRepository: UsageStatsRepository,
) {
    suspend fun launchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val usage = usageStatsRepository.perAppMinutesToday()
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return@withContext resolved
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != context.packageName }
            .map { info ->
                InstalledApp(
                    packageName = info.activityInfo.packageName,
                    label = info.loadLabel(pm).toString(),
                    minutesToday = usage[info.activityInfo.packageName] ?: 0L,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    fun launchIntentFor(packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)
}
