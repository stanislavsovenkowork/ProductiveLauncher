package dev.stanislav.focusdeck.guard;

import android.annotation.SuppressLint;
import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class GuardRuntime {
    static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static GuardController controller;
    // Constructed only from applicationContext; no Activity or receiver Context is retained.
    @SuppressLint("StaticFieldLeak")
    private static AndroidWakeScheduler scheduler;
    static synchronized GuardController controller(Context context) {
        if (controller == null) {
            Context storage = context.getApplicationContext().createDeviceProtectedStorageContext();
            controller = new GuardController(new AndroidGuardPlatform(storage), new AndroidJournalStore(storage), BuildConfig.DEBUG,
                    new GuardController.ScheduleLifecycle() {
                        @Override public void activated() { scheduler(storage).engine.activate(); }
                        @Override public void cancel() { scheduler(storage).engine.cancel(); }
                    });
        }
        return controller;
    }
    static synchronized AndroidWakeScheduler scheduler(Context context) {
        if (scheduler == null) scheduler = new AndroidWakeScheduler(context.getApplicationContext());
        return scheduler;
    }
    private GuardRuntime() {}
}
