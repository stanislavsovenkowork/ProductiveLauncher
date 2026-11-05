package dev.stanislav.focusdeck.qa

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import dev.stanislav.focusdeck.ui.restrictions.ProtectionWindowStore
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Uses one freshly named QA preferences file, never the real protection-window file. */
object ProtectionSnapshotProbe {
    fun run(context: Context): Bundle {
        val name = "qa-protection-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String?, mode: Int): SharedPreferences =
                super.getSharedPreferences(name, mode)
        }
        try {
            val session = UUID.randomUUID().toString()
            val policy = JSONObject().put("sessionId", session)
                .put("blockedDomains", JSONArray().put("blocked.test"))
                .put("allowedPackages", JSONArray().put(context.packageName))
                .put("durationSeconds", 1500).put("consentVpnReplacementRisk", true).toString()
            val first = ProtectionWindowStore(isolated)
            check(first.begin("study", emptySet(), session, null, policy))
            val restored = checkNotNull(ProtectionWindowStore(isolated).current())
            check(restored.packages.isEmpty() && restored.domainPolicyJson == policy && restored.sessionId == session)
            check(!first.begin("study", setOf("qa.probe"), UUID.randomUUID().toString(), null))
            check(runCatching { ProtectionWindowStore(isolated).begin("study", emptySet(), UUID.randomUUID().toString(), null, policy) }.isFailure)
            return Bundle().apply { putString("status", "ok"); putString("snapshot", "dns_only_restored_and_frozen") }
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
