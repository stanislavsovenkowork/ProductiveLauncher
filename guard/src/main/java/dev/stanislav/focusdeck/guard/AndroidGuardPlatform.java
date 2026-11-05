package dev.stanislav.focusdeck.guard;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageManager;
import android.os.UserManager;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class AndroidGuardPlatform implements GuardController.Platform {
    private final Context context;
    private final DevicePolicyManager policy;
    private final ComponentName admin;

    AndroidGuardPlatform(Context context) {
        this.context = context;
        policy = context.getSystemService(DevicePolicyManager.class);
        admin = new ComponentName(context, GuardAdminReceiver.class);
    }

    @Override public boolean eligibleOwner() {
        if (!policy.isAdminActive(admin)) return false;
        if (policy.isDeviceOwnerApp(context.getPackageName())) return true;
        return policy.isProfileOwnerApp(context.getPackageName())
                && !context.getSystemService(UserManager.class).isManagedProfile();
        // DPM also enforces affiliation/no-other-DO constraints on every policy call.
    }
    @Override public boolean secureDevice() {
        return context.getSystemService(KeyguardManager.class).isDeviceSecure();
    }
    @Override public boolean lockTaskIdle() {
        return context.getSystemService(ActivityManager.class).getLockTaskModeState()
                == ActivityManager.LOCK_TASK_MODE_NONE;
    }
    @Override public boolean trustedTargets(Set<String> packages) {
        PackageManager manager = context.getPackageManager();
        for (String target : packages) {
            if (manager.checkSignatures(context.getPackageName(), target) != PackageManager.SIGNATURE_MATCH) {
                return false;
            }
        }
        return true;
    }
    @Override public GuardController.Home defaultHome() {
        // Never restrict to our package: that would select a candidate, not the user's default.
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        // Validate configured Home even before first unlock; do not confuse an unavailable-to-launch
        // direct-boot-unaware Activity with Home drift and discard the active schedule on locked boot.
        ResolveInfo resolved = context.getPackageManager().resolveActivity(home,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
        return resolved == null || resolved.activityInfo == null ? null
                : new GuardController.Home(resolved.activityInfo.packageName, resolved.activityInfo.name);
    }
    @Override public GuardController.Policy readPolicy() {
        return new GuardController.Policy(new HashSet<>(Arrays.asList(policy.getLockTaskPackages(admin))),
                policy.getLockTaskFeatures(admin));
    }
    @Override public void setFeatures(int features) { policy.setLockTaskFeatures(admin, features); }
    @Override public void setPackages(Set<String> packages) {
        policy.setLockTaskPackages(admin, packages.toArray(new String[0]));
    }
}
