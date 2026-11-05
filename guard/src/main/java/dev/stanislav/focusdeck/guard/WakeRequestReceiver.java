package dev.stanislav.focusdeck.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Narrow signature-permission API; it cannot activate, recover, or change policy. */
public final class WakeRequestReceiver extends BroadcastReceiver {
    public static final String ACTION = "dev.stanislav.focusdeck.guard.SCHEDULE_WAKE";
    public static final String PERMISSION = "dev.stanislav.focusdeck.guard.permission.SCHEDULE_WAKE";
    public static final String EPOCH_MILLIS = "epochMillis";
    public static final String REASON = "reason";
    public static final String FOCUS_GRACE = "FOCUS_GRACE";
    public static final String EMERGENCY = "EMERGENCY";
    @Override public void onReceive(Context context, Intent intent) {
        AndroidWakeScheduler scheduler = GuardRuntime.scheduler(context);
        // Main must opt in with BroadcastOptions.setShareIdentityEnabled(true).
        if (!ACTION.equals(intent.getAction()) || !scheduler.trustedSender(getSentFromUid())) {
            setResultCode(2); setResultData("REFUSED_SENDER_OR_ACTION"); return;
        }
        WakeScheduler.Reason reason;
        long epoch;
        try {
            reason = WakeScheduler.parseReason(intent.getStringExtra(REASON));
            epoch = intent.getLongExtra(EPOCH_MILLIS, Long.MIN_VALUE);
        } catch (RuntimeException malformed) {
            setResultCode(2); setResultData("REFUSED_REASON_OR_PAYLOAD"); return;
        }
        if (reason == null) {
            setResultCode(2); setResultData("REFUSED_REASON_OR_PAYLOAD"); return;
        }
        PendingResult pending = goAsync();
        GuardRuntime.WORK.execute(() -> {
            try {
                boolean accepted = scheduler.engine.scheduleWake(epoch, reason);
                pending.setResultCode(accepted ? 0 : 2);
                pending.setResultData(accepted ? "WAKE_SCHEDULED; " + scheduler.status() : "REFUSED_INACTIVE_OR_PREREQUISITES_OR_BOUNDS_OR_LIMIT");
            } catch (RuntimeException failure) {
                pending.setResultCode(1); pending.setResultData("WAKE_NOT_VERIFIED");
            } finally { pending.finish(); }
        });
    }
}
