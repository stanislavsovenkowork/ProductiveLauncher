package dev.stanislav.focusdeck.ui.restrictions

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle

/** Android adapter. The private start-request record is distinct from vault ritual completion. */
class RitualRestrictionHost(private val activity: ComponentActivity, private val onStatus: (String, RestrictionController.Mode) -> Unit) {
    private val prefs = activity.getSharedPreferences("ritual-lock-ownership", Context.MODE_PRIVATE)
    private val manager get() = activity.getSystemService(ActivityManager::class.java)
    private val policy get() = activity.getSystemService(DevicePolicyManager::class.java)
    private var required: Boolean? = null

    fun update(value: Boolean) {
        required = value
        if (!canInteract()) return
        runCatching {
            val snapshot = snapshot()
            val ownership = storedOwnership()
            val ownedTask = ownership?.takeIf { it.matches(activity.taskId, bootCount(), rootPackage(), activity.packageName) }?.taskId
            if (snapshot.mode == RestrictionController.Mode.NONE) clearOwnership()
            val decision = RestrictionController.evaluate(RestrictionController.Request(value, ownedTask), snapshot)
            when (decision.action) {
                RestrictionController.Action.REQUEST_MANAGED_LOCK -> start()
                RestrictionController.Action.RELEASE_OWNED_LOCK -> {
                    activity.stopLockTask()
                    if (mode() == RestrictionController.Mode.NONE) clearOwnership()
                }
                RestrictionController.Action.NONE -> Unit
            }
            report(decision.releaseNeedsOwner)
        }.onFailure {
            onStatus("Ограничение Android не удалось проверить. Доступно восстановление через FocusDeck Guard с компьютера.", RestrictionController.Mode.UNKNOWN)
        }
    }

    fun pinExplicitly() {
        if (required != true || !canInteract()) return
        runCatching { start(); report(false) }
            .onFailure { onStatus("Не удалось закрепить экран. Проверь настройки закрепления Android.", mode()) }
    }

    fun onFocus() { required?.let(::update) }

    private fun start() {
        val home = activity.packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo
        check(home?.packageName == activity.packageName && home.name == activity.componentName.className) {
            "Сначала выбери FocusDeck домашним экраном Android"
        }
        check(rootPackage() == activity.packageName) { "The activity is not the root of its own task" }
        check(bootCount() >= 0) { "Cannot verify the current boot" }
        // Persist the request before the platform transition. A storage failure must not strand a task.
        check(prefs.edit().putInt("task", activity.taskId).putInt("boot", bootCount())
            .putString("root", activity.packageName).commit())
        try { activity.startLockTask() } catch (failure: RuntimeException) {
            clearOwnership(); throw failure
        }
    }

    private fun canInteract() = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        !activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun rootPackage(): String? = manager.appTasks.firstOrNull { it.taskInfo?.taskId == activity.taskId }
        ?.taskInfo?.baseActivity?.packageName

    private fun bootCount() = Settings.Global.getInt(activity.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private fun storedOwnership() = prefs.getString("root", null)?.let {
        LockOwnership(prefs.getInt("task", -1), prefs.getInt("boot", -1), it)
    }
    private fun clearOwnership() { if (prefs.contains("root")) prefs.edit().clear().apply() }
    private fun mode() = when (manager.lockTaskModeState) {
        ActivityManager.LOCK_TASK_MODE_NONE -> RestrictionController.Mode.NONE
        ActivityManager.LOCK_TASK_MODE_PINNED -> RestrictionController.Mode.PINNED
        ActivityManager.LOCK_TASK_MODE_LOCKED -> RestrictionController.Mode.LOCKED
        else -> RestrictionController.Mode.UNKNOWN
    }
    private fun snapshot() = RestrictionController.Snapshot(mode(), policy.isLockTaskPermitted(activity.packageName),
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked, activity.taskId)

    private fun report(needsOwner: Boolean) {
        val currentMode = mode()
        onStatus(when (currentMode) {
            RestrictionController.Mode.LOCKED -> if (needsOwner)
                "Ограничение запущено другой сессией. Для снятия используй восстановление FocusDeck Guard с компьютера."
            else "Включён режим ограничений Android. Системные панели ограничиваются политикой администратора."
            RestrictionController.Mode.PINNED -> "Экран закреплён Android. Закрепление можно снять системным жестом."
            RestrictionController.Mode.NONE -> "Ограничение действует внутри лаунчера. Для закрытия шторки и последних приложений нужен настроенный FocusDeck Guard."
            RestrictionController.Mode.UNKNOWN -> "Не удалось определить режим ограничений Android."
        }, currentMode)
    }
}
