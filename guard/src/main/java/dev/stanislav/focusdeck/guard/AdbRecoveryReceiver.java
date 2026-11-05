package dev.stanislav.focusdeck.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Exported ONLY behind framework-enforced android.permission.DUMP, for authorized ADB. */
public final class AdbRecoveryReceiver extends BroadcastReceiver {
    public static final String ACTIVATE = GuardCommands.ACTIVATE;
    public static final String RECOVER = GuardCommands.RECOVER;
    public static final String STATUS = GuardCommands.STATUS;

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!ACTIVATE.equals(action) && !RECOVER.equals(action) && !STATUS.equals(action)) {
            setResultCode(2);
            setResultData("Unsupported administrative operation.");
            return;
        }
        PendingResult pending = goAsync();
        GuardRuntime.WORK.execute(() -> {
            try {
                GuardController controller = GuardRuntime.controller(context);
                GuardController.Result result = GuardCommands.dispatch(controller, action);
                pending.setResultCode(result.code() == GuardController.Code.RESTORED
                        || result.code() == GuardController.Code.ACTIVE ? 0 :
                        result.code() == GuardController.Code.NEEDS_RECOVERY || result.code() == GuardController.Code.UNVERIFIED ? 1 : 2);
                pending.setResultData(result.code().name() + ": " + result.message()
                        + "\n" + GuardRuntime.scheduler(context).status());
            } catch (RuntimeException failure) {
                pending.setResultCode(1);
                pending.setResultData("Recovery/status unavailable. Use test-only admin removal if necessary.");
            } finally { pending.finish(); }
        });
    }
}
