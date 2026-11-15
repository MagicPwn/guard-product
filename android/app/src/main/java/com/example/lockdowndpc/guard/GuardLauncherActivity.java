package com.example.lockdowndpc.guard;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.example.lockdowndpc.R;
import com.example.lockdowndpc.ui.MainActivity;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The managed HOME launcher. It never grants policy exemptions; it only launches permitted apps. */
public final class GuardLauncherActivity extends AppCompatActivity {
    private LinearLayout appList;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                // HOME is the root surface; Back must not escape to another launcher.
            }
        });
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText(R.string.guard_launcher_title);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        root.addView(status);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        root.addView(appList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button refresh = new Button(this);
        refresh.setText(R.string.guard_launcher_refresh);
        refresh.setOnClickListener(view -> refresh());
        root.addView(refresh);
        Button console = new Button(this);
        console.setText(R.string.guard_launcher_console);
        console.setOnClickListener(view -> startActivity(new Intent(this, MainActivity.class)));
        root.addView(console);
    }

    private void refresh() {
        appList.removeAllViews();
        GuardPolicy policy;
        try {
            policy = GuardPolicyStore.read(this);
        } catch (RuntimeException exception) {
            status.setText(R.string.guard_degraded);
            return;
        }
        Map<String, Long> usage = UsageLedger.hasUsageAccess(this)
                ? UsageLedger.today(this, policy.timezone()) : java.util.Collections.emptyMap();
        Set<String> blocked = GuardPolicyEngine.blockedPackages(policy, usage,
                GuardRewardStore.active(this).keySet());
        Set<String> protectedPackages = GuardPolicyEnforcer.protectedPackages(this);
        int shown = 0;
        for (GuardRemoteClient.AppInfo app : GuardAppInventory.collect(this)) {
            if (!app.enabled || blocked.contains(app.packageName)
                    && !protectedPackages.contains(app.packageName)) continue;
            Button launch = new Button(this);
            launch.setText(app.label);
            launch.setOnClickListener(view -> launch(app.packageName));
            appList.addView(launch);
            shown++;
        }
        status.setText(getString(R.string.guard_launcher_status, shown,
                new HashSet<>(blocked).size()));
    }

    private void launch(String packageName) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            refresh();
            return;
        }
        try {
            startActivity(intent);
        } catch (RuntimeException exception) {
            status.setText(R.string.guard_degraded);
        }
    }
}
