package dev.stanislav.focusdeck.guard;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/** Enrollment and device-admin callbacks never apply policy or enter Lock Task. */
public final class GuardAdminReceiver extends DeviceAdminReceiver {
    @Override public void onDisabled(Context context, Intent intent) {
        try { GuardRuntime.scheduler(context).engine.cancel(); }
        catch (RuntimeException failure) { /* Each scheduled trigger also checks actual owner policy. */ }
    }
}
