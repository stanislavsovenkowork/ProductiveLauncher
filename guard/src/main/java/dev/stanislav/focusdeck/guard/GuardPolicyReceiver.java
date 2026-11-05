package dev.stanislav.focusdeck.guard;

import android.app.admin.DevicePolicyIdentifiers;
import android.app.admin.PolicyUpdateReceiver;
import android.app.admin.PolicyUpdateResult;
import android.app.admin.TargetUser;
import android.content.Context;
import android.os.Bundle;

/** Diagnostic only: a delayed callback cannot reactivate or undo a newer user decision. */
public final class GuardPolicyReceiver extends PolicyUpdateReceiver {
    @Override public void onPolicySetResult(Context context, String identifier, Bundle extras,
            TargetUser user, PolicyUpdateResult result) { record(context, identifier, result); }
    @Override public void onPolicyChanged(Context context, String identifier, Bundle extras,
            TargetUser user, PolicyUpdateResult result) { record(context, identifier, result); }
    private void record(Context context, String identifier, PolicyUpdateResult result) {
        if (!DevicePolicyIdentifiers.LOCK_TASK_POLICY.equals(identifier)) return;
        context.createDeviceProtectedStorageContext().getSharedPreferences("policy-diagnostic", Context.MODE_PRIVATE)
                .edit().putInt("last-result", result.getResultCode()).putLong("time", System.currentTimeMillis()).apply();
    }
}
