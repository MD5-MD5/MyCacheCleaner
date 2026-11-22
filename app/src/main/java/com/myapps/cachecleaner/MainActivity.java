package com.myapps.cachecleaner;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    private TextView statusBadge;
    private TextView summaryText;
    private CheckBox includeSystemCheck;
    private Button testButton;
    private Button startButton;
    private Button stopButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.parseColor("#F5F7FA"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.TOP);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left + dp(20), bars.top + dp(28), bars.right + dp(20), bars.bottom + dp(28));
            return insets;
        });

        TextView title = new TextView(this);
        title.setText("My Cache Cleaner");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTextColor(Color.parseColor("#111827"));
        title.getPaint().setFakeBoldText(true);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Automatically clears app caches without touching your personal data or logins.");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setTextColor(Color.parseColor("#4B5563"));
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle);

        statusBadge = new TextView(this);
        statusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusBadge.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(statusBadge, marginParams(0, 0, 0, dp(14)));

        Button accessibilityBtn = makeButton("1. Open Accessibility Settings", "#374151");
        accessibilityBtn.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibilityBtn, marginParams(0, 0, 0, dp(14)));

        includeSystemCheck = new CheckBox(this);
        includeSystemCheck.setText("Also scan hidden background/system apps (slower)");
        includeSystemCheck.setTextColor(Color.parseColor("#1F2937"));
        includeSystemCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        includeSystemCheck.setChecked(false);
        includeSystemCheck.setOnCheckedChangeListener((btn, checked) -> refreshUI());
        root.addView(includeSystemCheck, marginParams(0, 0, 0, dp(14)));

        testButton = makeButton("Test First 3 Apps (5-Second Check)", "#0D9488");
        testButton.setOnClickListener(v -> handleStartCleaning(3));
        root.addView(testButton, marginParams(0, 0, 0, dp(10)));

        startButton = makeButton("2. Clean All Everyday Apps", "#2563EB");
        startButton.setOnClickListener(v -> handleStartCleaning(0));
        root.addView(startButton, marginParams(0, 0, 0, dp(10)));

        stopButton = makeButton("Stop Cleaning", "#DC2626");
        stopButton.setOnClickListener(v -> {
            CacheCleanerService.stopCleaning();
            refreshUI();
            Toast.makeText(this, "Cleaning stopped.", Toast.LENGTH_SHORT).show();
        });
        root.addView(stopButton, marginParams(0, 0, 0, dp(18)));

        summaryText = new TextView(this);
        summaryText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        summaryText.setTextColor(Color.parseColor("#374151"));
        summaryText.setPadding(dp(14), dp(14), dp(14), dp(14));
        summaryText.setBackground(roundedBox("#FFFFFF", dp(12)));
        root.addView(summaryText, marginParams(0, 0, 0, dp(14)));

        TextView tipText = new TextView(this);
        tipText.setText("Tip: Run 'Test First 3 Apps' first to verify speed. Keep your screen unlocked until it returns here.");
        tipText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tipText.setTextColor(Color.parseColor("#6B7280"));
        root.addView(tipText);

        scrollView.addView(root);
        setContentView(scrollView);

        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUI();
    }

    private void handleStartCleaning(int limit) {
        if (!isAccessibilityEnabled() || !CacheCleanerService.isServiceConnected()) {
            Toast.makeText(this, "Turn ON 'My Cache Cleaner' in Accessibility Settings first.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }

        List<String> packages = getTargetPackages(includeSystemCheck.isChecked());
        if (packages.isEmpty()) {
            Toast.makeText(this, "No apps found to clean.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (limit > 0 && packages.size() > limit) {
            packages = new ArrayList<>(packages.subList(0, limit));
        }

        boolean started = CacheCleanerService.startCleaning(packages);
        if (started) {
            Toast.makeText(this, "Cleaning " + packages.size() + " apps...", Toast.LENGTH_SHORT).show();
            refreshUI();
        } else {
            Toast.makeText(this, "Toggle Accessibility OFF and ON again, then retry.", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshUI() {
        boolean enabled = isAccessibilityEnabled() && CacheCleanerService.isServiceConnected();
        if (enabled) {
            statusBadge.setText("Accessibility Service: ON (Ready)");
            statusBadge.setTextColor(Color.parseColor("#065F46"));
            statusBadge.setBackground(roundedBox("#D1FAE5", dp(10)));
        } else {
            statusBadge.setText("Accessibility Service: OFF — Tap button 1 below to enable");
            statusBadge.setTextColor(Color.parseColor("#991B1B"));
            statusBadge.setBackground(roundedBox("#FEE2E2", dp(10)));
        }

        int count = getTargetPackages(includeSystemCheck.isChecked()).size();
        startButton.setText("2. Clean All (" + count + " Apps)");

        boolean running = CacheCleanerService.isRunning();
        testButton.setEnabled(!running);
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        stopButton.setAlpha(running ? 1.0f : 0.45f);

        String debug = CacheCleanerService.getLastDebug();
        String status = "Status: " + CacheCleanerService.getLastSummary();
        if (debug != null && !debug.isEmpty()) {
            status += "\n\nDiagnostic: " + debug;
        }
        summaryText.setText(status);
    }

    private boolean isAccessibilityEnabled() {
        ComponentName cn = new ComponentName(this, CacheCleanerService.class);
        String expectedFull = cn.flattenToString();
        String expectedShort = cn.flattenToShortString();
        String enabled = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabled == null) return false;

        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            String service = splitter.next();
            if (service.equalsIgnoreCase(expectedFull) || service.equalsIgnoreCase(expectedShort)) {
                return true;
            }
        }
        return false;
    }

    private List<String> getTargetPackages(boolean includeSystem) {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        List<String> packages = new ArrayList<>();
        String myPkg = getPackageName();

        for (ApplicationInfo info : apps) {
            String pkg = info.packageName;
            if (pkg.equals(myPkg) || pkg.contains("settings")) continue;

            if (!includeSystem) {
                if (pm.getLaunchIntentForPackage(pkg) == null) {
                    continue;
                }
            }
            packages.add(pkg);
        }
        Collections.sort(packages);
        return packages;
    }

    private Button makeButton(String label, String hexColor) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setPadding(dp(16), dp(13), dp(16), dp(13));
        btn.setBackground(roundedBox(hexColor, dp(12)));
        return btn;
    }

    private GradientDrawable roundedBox(String hexColor, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(hexColor));
        gd.setCornerRadius(radiusPx);
        return gd;
    }

    private LinearLayout.LayoutParams marginParams(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
