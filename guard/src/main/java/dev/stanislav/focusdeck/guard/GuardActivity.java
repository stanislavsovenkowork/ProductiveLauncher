package dev.stanislav.focusdeck.guard;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** No Activity intent extra/action can activate or recover. UI operations require fresh consent. */
public final class GuardActivity extends Activity {
    private static final int CONFIRM_CREDENTIAL = 7301;
    private enum Operation { ACTIVATE, RECOVER }
    private Operation pending;
    private TextView status;
    private Button activate;
    private Button recover;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setHideOverlayWindows(true);
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        column.setPadding(pad, pad * 2, pad, pad);
        scroll.addView(column);
        TextView title = new TextView(this);
        title.setText("FocusDeck Guard");
        title.setTextSize(28);
        column.addView(title);
        TextView info = new TextView(this);
        info.setText("Dormant until deliberately enrolled AND activated.\n\n"
                + (BuildConfig.DEBUG ? "Debug build: allows only the QA launcher with the same signing certificate. "
                        : "Release build: allows only production FocusDeck with the same signing certificate. ")
                + "Keeps the lock screen and power menu available. "
                + "Does not start a ritual or lock a task itself.\n\n"
                + "Enrollment must be done separately after testing ADB recovery. No account removal or reset is needed by this app. "
                + "Existing accounts may prevent Android from allowing enrollment.\n");
        info.setTextSize(17);
        column.addView(info);
        status = new TextView(this);
        status.setTextSize(17);
        column.addView(status);
        activate = button(column, "Activate policy…", () -> confirm(Operation.ACTIVATE));
        recover = button(column, "Recover previous policy…", () -> confirm(Operation.RECOVER));
        button(column, "Refresh status", this::refresh);
        TextView recoveryInfo = new TextView(this);
        recoveryInfo.setText("Independent recovery: authorized ADB can call AdbRecoveryReceiver, even if FocusDeck or its vault fails. "
                + "Recovery removes Guard's Lock Task policy; Guard stays enrolled. Keep USB debugging and the authorized computer available during testing.");
        column.addView(recoveryInfo);
        setContentView(scroll);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private Button button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setFilterTouchesWhenObscured(true);
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
        return button;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if ((event.getFlags() & (MotionEvent.FLAG_WINDOW_IS_OBSCURED | MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)) != 0) return true;
        return super.dispatchTouchEvent(event);
    }

    private void confirm(Operation operation) {
        if (busy || pending != null) return;
        new AlertDialog.Builder(this)
                .setTitle(operation == Operation.ACTIVATE ? "Activate Guard policy?" : "Remove Guard policy and recover?")
                .setMessage(operation == Operation.ACTIVATE
                        ? "Proceed only after the ADB recovery and test-only admin removal paths are verified. "
                        + "FocusDeck may enter managed Lock Task as soon as its package is allowlisted. "
                        + "Keyguard and power controls remain available."
                        : "Remove Guard's policy and verify the saved settings. This may end FocusDeck's locked task. "
                        + "No ritual will be marked complete; Guard stays enrolled.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Confirm with device credential", (dialog, which) -> authenticate(operation))
                .show().getWindow().setHideOverlayWindows(true);
    }

    @SuppressWarnings("deprecation")
    private void authenticate(Operation operation) {
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        Intent confirmation = keyguard.createConfirmDeviceCredentialIntent("FocusDeck Guard", "Confirm this policy change");
        if (!keyguard.isDeviceSecure() || confirmation == null) {
            status.setText("A device credential is required. ADB recovery remains independent of this screen.");
            return;
        }
        pending = operation;
        startActivityForResult(confirmation, CONFIRM_CREDENTIAL);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CONFIRM_CREDENTIAL) return;
        Operation operation = pending;
        pending = null;
        if (result != RESULT_OK || operation == null) return;
        busy = true;
        activate.setEnabled(false);
        recover.setEnabled(false);
        GuardRuntime.WORK.execute(() -> {
            GuardController controller = GuardRuntime.controller(this);
            GuardController.Result outcome = operation == Operation.ACTIVATE ? controller.activate(true) : controller.recover();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                busy = false;
                activate.setEnabled(true);
                recover.setEnabled(true);
                show(outcome);
            });
        });
    }

    private void refresh() {
        if (status == null || busy) return;
        GuardRuntime.WORK.execute(() -> {
            GuardController.Result result = GuardRuntime.controller(this).status();
            runOnUiThread(() -> { if (!isDestroyed() && !busy) show(result); });
        });
    }

    private void show(GuardController.Result result) {
        int mode = getSystemService(ActivityManager.class).getLockTaskModeState();
        String lock = mode == ActivityManager.LOCK_TASK_MODE_LOCKED ? "LOCKED"
                : mode == ActivityManager.LOCK_TASK_MODE_PINNED ? "PINNED (removable)" : "NONE";
        int callback = createDeviceProtectedStorageContext().getSharedPreferences("policy-diagnostic", MODE_PRIVATE)
                .getInt("last-result", Integer.MIN_VALUE);
        status.setText(result.code() + "\n" + result.message() + "\n\nObserved task mode: " + lock
                + "\nLast policy callback code: " + (callback == Integer.MIN_VALUE ? "not received" : callback)
                + " (diagnostic only)\n" + GuardRuntime.scheduler(this).status() + "\n");
    }
}
