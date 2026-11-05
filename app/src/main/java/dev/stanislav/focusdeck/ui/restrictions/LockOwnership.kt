package dev.stanislav.focusdeck.ui.restrictions

/** A private, persisted start request can survive process death, but never a reboot or another task. */
data class LockOwnership(val taskId: Int, val bootCount: Int, val rootPackage: String) {
    fun matches(currentTask: Int, currentBoot: Int, currentRoot: String?, ownPackage: String): Boolean =
        taskId >= 0 && bootCount >= 0 && currentTask == taskId && currentBoot == bootCount &&
            rootPackage == ownPackage && currentRoot == ownPackage
}
