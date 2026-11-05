package dev.stanislav.focusdeck.guard;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Not exported. Only own immutable alarm PendingIntents and system lifecycle broadcasts. */
public final class GuardScheduleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        boolean daily = AndroidWakeScheduler.DAILY.equals(action);
        boolean wake = AndroidWakeScheduler.WAKE.equals(action);
        boolean reschedule = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action);
        if (!daily && !wake && !reschedule) return;
        long generation = intent.getLongExtra("generation", -1);
        long at = intent.getLongExtra("scheduledAt", -1);
        PendingResult pending = goAsync();
        GuardRuntime.WORK.execute(() -> {
            AndroidWakeScheduler scheduler = GuardRuntime.scheduler(context);
            try {
                if (reschedule) scheduler.engine.reschedule();
                else scheduler.engine.fire(daily ? WakeScheduler.Kind.DAILY : WakeScheduler.Kind.WAKE, generation, at);
            } catch (RuntimeException failure) { scheduler.note("SCHEDULE_OR_LAUNCH_FAILED"); }
            finally { pending.finish(); }
        });
    }
}
