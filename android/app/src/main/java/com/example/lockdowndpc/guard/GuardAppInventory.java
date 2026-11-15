package com.example.lockdowndpc.guard;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.example.lockdowndpc.security.AppLabelSanitizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Collects the launchable app inventory the parent needs to author policies. */
public final class GuardAppInventory {
    private static final int MAX_APPS = 2000;

    private GuardAppInventory() {}

    public static List<GuardRemoteClient.AppInfo> collect(Context context) {
        PackageManager packageManager = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = packageManager.queryIntentActivities(
                launcher, PackageManager.MATCH_ALL);
        List<GuardRemoteClient.AppInfo> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null || info.activityInfo.applicationInfo == null) continue;
            if (!info.activityInfo.enabled || !info.activityInfo.applicationInfo.enabled) continue;
            String packageName = info.activityInfo.packageName;
            if (packageName == null || packageName.equals(context.getPackageName())
                    || !seen.add(packageName)) continue;
            String label;
            try {
                ApplicationInfo application = packageManager.getApplicationInfo(packageName, 0);
                label = String.valueOf(packageManager.getApplicationLabel(application));
            } catch (PackageManager.NameNotFoundException exception) {
                continue;
            }
            label = AppLabelSanitizer.sanitize(label).trim();
            if (label.isEmpty()) label = packageName;
            if (label.length() > 120) label = label.substring(0, 120);
            int category;
            try {
                category = packageManager.getApplicationInfo(packageName, 0).category;
            } catch (PackageManager.NameNotFoundException exception) {
                category = ApplicationInfo.CATEGORY_UNDEFINED;
            }
            apps.add(new GuardRemoteClient.AppInfo(packageName, label, category, true));
        }
        apps.sort(Comparator.comparing(app -> app.label, String.CASE_INSENSITIVE_ORDER));
        if (apps.size() > MAX_APPS) return new ArrayList<>(apps.subList(0, MAX_APPS));
        return apps;
    }
}
