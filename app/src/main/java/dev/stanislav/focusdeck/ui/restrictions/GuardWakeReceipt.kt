package dev.stanislav.focusdeck.ui.restrictions

/** An alarm receipt is not proof that Android later opened the launcher. */
data class GuardWakeReceipt(val registered: Boolean, val backgroundLaunchSupported: Boolean, val exactRequested: Boolean) {
    val canRelyOnManagedReturn: Boolean get() = registered && backgroundLaunchSupported && exactRequested

    companion object {
        val UNAVAILABLE = GuardWakeReceipt(false, false, false)
        fun fromResult(code: Int, diagnostic: String?): GuardWakeReceipt {
            val text = diagnostic.orEmpty()
            fun has(token: String) = Regex("(?:^|[;,\\s])${Regex.escape(token)}(?:$|[;,\\s])").containsMatchIn(text)
            val accepted = code == 0 && text.startsWith("WAKE_SCHEDULED;")
            return GuardWakeReceipt(accepted, accepted && has("ownerBAL=true"),
                accepted && has("exactAllowed=true") && has("precision=EXACT_REQUESTED"))
        }
    }
}
