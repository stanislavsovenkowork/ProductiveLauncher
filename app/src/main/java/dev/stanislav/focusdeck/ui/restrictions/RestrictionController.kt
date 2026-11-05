package dev.stanislav.focusdeck.ui.restrictions

/**
 * Pure decision controller; does not grant privileges or call Android APIs.
 * The activity must sample Android immediately before acting, then sample again afterwards.
 * A requested action is never evidence that Android applied it.
 */
object RestrictionController {
    enum class Mode { NONE, PINNED, LOCKED, UNKNOWN }
    enum class Action { NONE, REQUEST_MANAGED_LOCK, RELEASE_OWNED_LOCK }

    data class Snapshot(
        val mode: Mode,
        val packageAllowlisted: Boolean,
        val activityResumed: Boolean,
        val deviceLocked: Boolean,
        val taskId: Int,
    )

    /**
     * [ownedTaskId] is supplied only for a startLockTask request made by this activity session.
     * Do not infer ownership from LOCKED or restore it from a vault/completion marker.
     * A private persisted start request may be restored only after LockOwnership validates
     * boot, task and root package. Unverifiable sessions require managing-owner recovery.
     */
    data class Request(val ritualRequired: Boolean, val ownedTaskId: Int? = null)

    data class Decision(
        val observedMode: Mode,
        val action: Action,
        val releaseNeedsOwner: Boolean,
    )

    fun evaluate(request: Request, snapshot: Snapshot): Decision {
        val ownsTask = snapshot.taskId >= 0 && request.ownedTaskId == snapshot.taskId
        val active = snapshot.mode == Mode.PINNED || snapshot.mode == Mode.LOCKED
        val canInteract = snapshot.activityResumed && !snapshot.deviceLocked && snapshot.taskId >= 0
        val action = when {
            !canInteract -> Action.NONE
            !request.ritualRequired && active && ownsTask -> Action.RELEASE_OWNED_LOCK
            request.ritualRequired && snapshot.mode == Mode.NONE && snapshot.packageAllowlisted ->
                Action.REQUEST_MANAGED_LOCK
            else -> Action.NONE
        }
        return Decision(
            observedMode = snapshot.mode,
            action = action,
            releaseNeedsOwner = !request.ritualRequired && active && !ownsTask,
        )
    }
}
