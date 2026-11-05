package dev.stanislav.focusdeck.guard;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.os.UserManager;
import java.time.ZoneId;

final class AndroidWakeScheduler implements WakeScheduler.Port, WakeScheduler.Store {
    static final String DAILY = "dev.stanislav.focusdeck.guard.INTERNAL_DAILY";
    static final String WAKE = "dev.stanislav.focusdeck.guard.INTERNAL_WAKE";
    final WakeScheduler engine;
    private final Context context;
    private final SharedPreferences prefs;
    private final AlarmManager alarms;
    AndroidWakeScheduler(Context context) {
        this.context = context.createDeviceProtectedStorageContext();
        prefs = this.context.getSharedPreferences("wake-schedule", Context.MODE_PRIVATE);
        alarms = this.context.getSystemService(AlarmManager.class);
        engine = new WakeScheduler(this, this);
    }
    static String targetPackage() {
        return BuildConfig.DEBUG ? GuardController.QA_PACKAGE : GuardController.PRODUCTION_PACKAGE;
    }
    @Override public long now() { return System.currentTimeMillis(); }
    @Override public ZoneId zone() { return ZoneId.systemDefault(); }
    @Override public boolean activePolicy() {
        return GuardRuntime.controller(context).status().code() == GuardController.Code.ACTIVE;
    }
    @Override public boolean hasBackgroundLaunchAuthority() {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        return dpm.isDeviceOwnerApp(context.getPackageName())
                || (dpm.isProfileOwnerApp(context.getPackageName()) && dpm.isAffiliatedUser()
                    && !context.getSystemService(UserManager.class).isManagedProfile());
    }
    @Override public boolean exactAlarmsAvailable() { return alarms.canScheduleExactAlarms(); }
    @Override public boolean unlockedForegroundUser() {
        UserManager users = context.getSystemService(UserManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return users.isUserUnlocked() && users.isUserForeground()
                && !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked()
                && context.getSystemService(PowerManager.class).isInteractive();
    }
    private Intent alarmIntent(WakeScheduler.Kind kind) {
        return new Intent(context, GuardScheduleReceiver.class).setAction(kind == WakeScheduler.Kind.DAILY ? DAILY : WAKE);
    }
    @Override public void arm(WakeScheduler.Kind kind, long at, long generation) {
        boolean exact = alarms.canScheduleExactAlarms();
        if (kind == WakeScheduler.Kind.WAKE && !exact) {
            throw new SecurityException("Exact alarm access required for a managed pause");
        }
        PendingIntent operation = PendingIntent.getBroadcast(context, kind.ordinal(),
                alarmIntent(kind).putExtra("generation", generation).putExtra("scheduledAt", at),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (exact) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation);
                prefs.edit().putString("precision", "EXACT_REQUESTED").apply();
                return;
            } catch (SecurityException revoked) {
                // Daily may degrade; WAKE must roll back its reservation, not consume a denied pause.
                if (kind == WakeScheduler.Kind.WAKE) throw revoked;
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation);
        prefs.edit().putString("precision", "INEXACT_MAY_BE_DELAYED").apply();
    }
    @Override public void cancel(WakeScheduler.Kind kind) {
        PendingIntent operation = PendingIntent.getBroadcast(context, kind.ordinal(), alarmIntent(kind),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (operation != null) { alarms.cancel(operation); operation.cancel(); }
    }
    @Override public void launchFixedTarget() {
        // Recheck immediately before launch. No turnScreenOn/showWhenLocked or keyguard dismissal.
        if (!activePolicy() || !hasBackgroundLaunchAuthority() || !unlockedForegroundUser()) {
            throw new IllegalStateException("Launch eligibility changed");
        }
        Intent launch = Intent.makeMainActivity(new ComponentName(targetPackage(), GuardController.TARGET_ACTIVITY));
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        context.startActivity(launch);
    }
    @Override public void note(String diagnostic) { prefs.edit().putString("diagnostic", diagnostic).apply(); }
    @Override public WakeScheduler.State read() {
        if (!prefs.contains("enabled")) return WakeScheduler.State.empty();
        int version = prefs.getInt("version", 0);
        if (version == 1) {
            WakeScheduler.State migrated = WakeScheduler.migrateV1(prefs.getBoolean("enabled", false),
                    prefs.getLong("generation", 0), prefs.getLong("dailyAt", 0), prefs.getLong("wakeAt", 0),
                    prefs.getLong("lastDailyDay", Long.MIN_VALUE), prefs.getLong("lastWakeRequest", 0),
                    prefs.getLong("lastAttempt", 0), zone());
            write(migrated); // Atomic commit before any alarm operation or successful receipt.
            return migrated;
        }
        if (version != 2) throw new IllegalStateException("Unknown schedule version");
        return new WakeScheduler.State(prefs.getBoolean("enabled", false), prefs.getLong("generation", 0),
                prefs.getLong("dailyAt", 0), prefs.getLong("wakeAt", 0), prefs.getLong("lastDailyDay", Long.MIN_VALUE),
                prefs.getLong("lastWakeRequest", 0), prefs.getLong("lastAttempt", 0),
                prefs.getString("wakeReason", "NONE"), prefs.getLong("graceDay", Long.MIN_VALUE),
                prefs.getLong("emergencyWindow", Long.MIN_VALUE), prefs.getLong("legacyUntil", 0));
    }
    @Override public void write(WakeScheduler.State state) {
        if (!prefs.edit().putInt("version", 2).putBoolean("enabled", state.enabled())
                .putLong("generation", state.generation()).putLong("dailyAt", state.dailyAt())
                .putLong("wakeAt", state.wakeAt()).putLong("lastDailyDay", state.lastDailyDay())
                .putLong("lastWakeRequest", state.lastWakeRequest()).putLong("lastAttempt", state.lastAttempt())
                .putString("wakeReason", state.wakeReason()).putLong("graceDay", state.graceDay())
                .putLong("emergencyWindow", state.emergencyWindow()).putLong("legacyUntil", state.legacyUntil()).commit()) {
            throw new IllegalStateException("Cannot persist schedule");
        }
    }
    boolean trustedSender(int uid) {
        if (uid < 0) return false;
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getPackageUid(targetPackage(), 0) == uid
                    && pm.checkSignatures(context.getPackageName(), targetPackage()) == PackageManager.SIGNATURE_MATCH;
        } catch (PackageManager.NameNotFoundException missing) { return false; }
    }
    String status() {
        try {
            WakeScheduler.State state = read();
            return "Schedule enabled=" + state.enabled() + ", next23=" + state.dailyAt() + ", wake=" + state.wakeAt()
                    + ", reason=" + state.wakeReason() + ", schema=2"
                    + ", exactAllowed=" + alarms.canScheduleExactAlarms()
                    + ", ownerBAL=" + hasBackgroundLaunchAuthority()
                    + ", precision=" + prefs.getString("precision", "NONE")
                    + ", last=" + prefs.getString("diagnostic", "NONE");
        } catch (RuntimeException failure) { return "Schedule UNVERIFIED"; }
    }
}
