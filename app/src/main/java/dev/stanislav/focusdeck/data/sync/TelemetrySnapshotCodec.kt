package dev.stanislav.focusdeck.data.sync

import java.time.Instant
import java.util.UUID
import org.json.JSONObject

/** Projection of typed metadata only. Free text, outcomes, titles, URLs, query names and unknown keys never leave here. */
internal object TelemetrySnapshotCodec {
    private val types=setOf("started","paused","resumed","completed","stopped","missed","disabled","uninstalled","heartbeat","health","maintenance")
    private val reasons=setOf("clock_or_process_gap","accessibility_connected","accessibility_disconnected","user_disabled",
        "launcher_attempt_denied","system_app_denied","bounded_relief_started","rest_without_feeds","rest_returned","bounded_relief_expired","relief_ended_early","ritual_optout","window_started","protection_started","protection_expired","protection_exit",
        "dns_consent_declined","dns_consent_required","dns_future_windows_disabled","dns_refused_existing_vpn","dns_refused_network_or_private_dns",
        "dns_refused_notifications","dns_service_unavailable","dns_settings_incomplete","dns_settings_unavailable","dns_snapshot_mismatch",
        "dns_trial_active_partial","dns_trial_inactive","dns_preflight_unavailable","dns_refused_safety_scope",
        "persisted_known_interval_before_relaunch_gap","relaunch_gap_unknown","bounded_break_300_seconds","explicit_return",
        "known_interval_before_unknown_gap","suspend_or_clock_gap_unknown","nominal_target_ended_accounting_continues_until_explicit_stop",
        "bounded_break_ended_explicit_return_needed","measured_live_timer_segment","focus_preserved_existing","focus_requested_until","focus_unverified")
    internal fun safeReason(value:String?):String?=value?.takeIf { it in reasons }
    fun entries(jsonl:String,expectedSource:String="android",strict:Boolean=false):List<SyncEntry> = jsonl.lineSequence().filter(String::isNotBlank).map { line ->
        val row=JSONObject(line)
        if(strict)require(row.keys().asSequence().all { it in setOf("schemaVersion","eventId","sessionId","occurredAt","source","category","type","evidence","metrics","startedAt","endedAt","coverage","reason","taskRef","protectionId","mode","protected") })
        require(expectedSource in setOf("android","mac"))
        require(row.getInt("schemaVersion")==1 && row.getString("source")==expectedSource)
        val id=uuid(row.getString("eventId"))
        val fields=linkedMapOf<String,String>("schemaVersion" to "1","eventId" to quote(id))
        fun nullable(key:String):String? { require(row.has(key));return if(row.isNull(key))null else row.getString(key) }
        fields["sessionId"]=nullable("sessionId")?.let { quote(uuid(it)) } ?: "null"
        fields["occurredAt"]=quote(timestamp(row.getString("occurredAt")))
        fields["source"]=quote(expectedSource)
        val category=nullable("category");require(category==null || category in setOf("work","study"));fields["category"]=category?.let(::quote) ?: "null"
        val type=row.getString("type");require(type in types);fields["type"]=quote(type)
        val evidence=row.getString("evidence");require(evidence in setOf("timer","activity","self_report","verified","unknown"));fields["evidence"]=quote(evidence)
        val metrics=row.getJSONObject("metrics")
        val allowed=setOf("focusSeconds","distractionSeconds","maintenanceSeconds")
        val keys=metrics.keys().asSequence().toList();require(keys.all { it in allowed })
        fields["metrics"]="{"+keys.sorted().joinToString(",") { key ->
            val value=metrics.get(key)
            val encoded=if(value===JSONObject.NULL)"null" else {require(value is Number && value.toDouble().isFinite() && value.toDouble()>=0);value.toDouble().toString()}
            quote(key)+":"+encoded
        }+"}"
        for(key in listOf("startedAt","endedAt"))if(row.has(key))fields[key]=if(row.isNull(key))"null" else quote(timestamp(row.getString(key)))
        if(row.has("coverage")){val value=if(row.isNull("coverage"))null else row.getString("coverage");require(value==null || value in setOf("complete","partial","missing"));fields["coverage"]=value?.let(::quote) ?: "null"}
        if(row.has("reason")){val value=if(row.isNull("reason"))null else row.getString("reason");if(value==null)fields["reason"]="null" else safeReason(value)?.let { fields["reason"]=quote(it) }}
        if(row.has("taskRef")){val value=if(row.isNull("taskRef"))null else row.getString("taskRef");require(value==null || value.matches(Regex("[A-Za-z0-9_-]{1,128}")));fields["taskRef"]=value?.let(::quote) ?: "null"}
        if(row.has("protectionId"))fields["protectionId"]=if(row.isNull("protectionId"))"null" else quote(uuid(row.getString("protectionId")))
        if(row.has("mode")){require(row.get("mode")=="timer");fields["mode"]=quote("timer")}
        if(row.has("protected")){require(row.get("protected")==false);fields["protected"]="false"}
        if(fields["startedAt"]!=null && fields["startedAt"]!="null" && fields["endedAt"]!=null && fields["endedAt"]!="null")
            require(Instant.parse(row.getString("endedAt"))>=Instant.parse(row.getString("startedAt")))
        SyncEntry(id,"{"+fields.entries.joinToString(","){quote(it.key)+":"+it.value}+"}")
    }.toList()
    private fun uuid(value:String):String {require(UUID.fromString(value).toString()==value);return value}
    private fun timestamp(value:String):String {require(value.endsWith("Z") || value.endsWith("+00:00"));Instant.parse(value);return value}
    private fun quote(value:String):String = "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\""
}
