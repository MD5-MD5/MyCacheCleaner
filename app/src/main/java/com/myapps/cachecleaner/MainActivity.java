package com.myapps.cachecleaner;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final long MIN_CLEARABLE_CACHE_BYTES = 16L * 1024L;

    private static class CachedApp {
        final String appName;
        final String packageName;
        final long cacheBytes;
        boolean selected = true;

        CachedApp(String appName, String packageName, long cacheBytes) {
            this.appName = appName;
            this.packageName = packageName;
            this.cacheBytes = cacheBytes;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<CachedApp> scannedApps = new ArrayList<>();

    private TextView usageBadge;
    private TextView accessBadge;
    private TextView summaryText;
    private TextView listHeaderText;
    private CheckBox turboCheck;
    private CheckBox includeSystemCheck;
    private Button scanButton;
    private Button testButton;
    private Button cleanButton;
    private Button stopButton;
    private Button toggleSelectButton;
    private LinearLayout listContainer;

    private boolean isScanning = false;

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
            v.setPadding(bars.left + dp(18), bars.top + dp(22), bars.right + dp(18), bars.bottom + dp(24));
            return insets;
        });

        TextView title = new TextView(this);
        title.setText("My Cache Cleaner");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 25);
        title.setTextColor(Color.parseColor("#111827"));
        title.getPaint().setFakeBoldText(true);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Scans exact app cache sizes and turbo-cleans selected apps safely.");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setTextColor(Color.parseColor("#4B5563"));
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        usageBadge = new TextView(this);
        usageBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        usageBadge.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(usageBadge, marginParams(0, 0, 0, dp(8)));

        accessBadge = new TextView(this);
        accessBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        accessBadge.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(accessBadge, marginParams(0, 0, 0, dp(12)));

        LinearLayout permRow = new LinearLayout(this);
        permRow.setOrientation(LinearLayout.HORIZONTAL);
        Button usageBtn = makeButton("1. Usage Access", "#475569", 13);
        usageBtn.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        Button accessBtn = makeButton("2. Accessibility", "#334155", 13);
        accessBtn.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        permRow.addView(usageBtn, weightParams(0, 0, dp(6), 0));
        permRow.addView(accessBtn, weightParams(dp(6), 0, 0, 0));
        root.addView(permRow, marginParams(0, 0, 0, dp(12)));

        turboCheck = new CheckBox(this);
        turboCheck.setText("Turbo Mode (fastest, clears under a minute)");
        turboCheck.setTextColor(Color.parseColor("#111827"));
        turboCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        turboCheck.setChecked(true);
        root.addView(turboCheck, marginParams(0, 0, 0, dp(4)));

        includeSystemCheck = new CheckBox(this);
        includeSystemCheck.setText("Also scan hidden background/system apps");
        includeSystemCheck.setTextColor(Color.parseColor("#374151"));
        includeSystemCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        includeSystemCheck.setChecked(false);
        root.addView(includeSystemCheck, marginParams(0, 0, 0, dp(12)));

        scanButton = makeButton("Scan Apps for Cache", "#0D9488", 15);
        scanButton.setOnClickListener(v -> startScan());
        root.addView(scanButton, marginParams(0, 0, 0, dp(10)));

        cleanButton = makeButton("Turbo Clean Selected (Scan First)", "#2563EB", 15);
        cleanButton.setOnClickListener(v -> handleStartCleaning(0));
        root.addView(cleanButton, marginParams(0, 0, 0, dp(10)));

        LinearLayout auxRow = new LinearLayout(this);
        auxRow.setOrientation(LinearLayout.HORIZONTAL);
        testButton = makeButton("Test Top 3 Apps", "#4F46E5", 13);
        testButton.setOnClickListener(v -> handleStartCleaning(3));
        stopButton = makeButton("Stop", "#DC2626", 13);
        stopButton.setOnClickListener(v -> {
            CacheCleanerService.stopCleaning();
            refreshUI();
            Toast.makeText(this, "Cleaning stopped.", Toast.LENGTH_SHORT).show();
        });
        auxRow.addView(testButton, weightParams(0, 0, dp(6), 0));
        auxRow.addView(stopButton, weightParams(dp(6), 0, 0, 0));
        root.addView(auxRow, marginParams(0, 0, 0, dp(14)));

        summaryText = new TextView(this);
        summaryText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        summaryText.setTextColor(Color.parseColor("#1F2937"));
        summaryText.setPadding(dp(12), dp(12), dp(12), dp(12));
        summaryText.setBackground(roundedBox("#FFFFFF", dp(10)));
        root.addView(summaryText, marginParams(0, 0, 0, dp(14)));

        LinearLayout listHeaderRow = new LinearLayout(this);
        listHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        listHeaderRow.setGravity(Gravity.CENTER_VERTICAL);

        listHeaderText = new TextView(this);
        listHeaderText.setText("Apps With Cache (Not scanned yet)");
        listHeaderText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        listHeaderText.setTextColor(Color.parseColor("#111827"));
        listHeaderText.getPaint().setFakeBoldText(true);
        listHeaderRow.addView(listHeaderText, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        toggleSelectButton = makeButton("Select / Clear", "#64748B", 12);
        toggleSelectButton.setPadding(dp(10), dp(6), dp(10), dp(6));
        toggleSelectButton.setVisibility(View.GONE);
        toggleSelectButton.setOnClickListener(v -> toggleSelectAll());
        listHeaderRow.addView(toggleSelectButton);
        root.addView(listHeaderRow, marginParams(0, 0, 0, dp(8)));

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(dp(10), dp(8), dp(10), dp(8));
        listContainer.setBackground(roundedBox("#FFFFFF", dp(12)));
        root.addView(listContainer, marginParams(0, 0, 0, dp(16)));

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
        if (CacheCleanerService.consumeJustFinished() && hasUsageAccess()) {
            startScan();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void startScan() {
        if (isScanning) return;
        if (!hasUsageAccess()) {
            Toast.makeText(this, "Turn ON Usage Access for My Cache Cleaner first.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            return;
        }

        isScanning = true;
        final boolean includeSystem = includeSystemCheck.isChecked();
        scanButton.setEnabled(false);
        scanButton.setText("Scanning apps...");
        listHeaderText.setText("Scanning installed apps...");

        executor.execute(() -> {
            List<CachedApp> found = scanInstalledCaches(includeSystem);
            mainHandler.post(() -> {
                isScanning = false;
                scannedApps.clear();
                scannedApps.addAll(found);
                renderScannedList();
                refreshUI();
            });
        });
    }

    private List<CachedApp> scanInstalledCaches(boolean includeSystem) {
        PackageManager pm = getPackageManager();
        StorageStatsManager ssm = (StorageStatsManager) getSystemService(Context.STORAGE_STATS_SERVICE);
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        List<CachedApp> result = new ArrayList<>();
        if (ssm == null) return result;

        String myPkg = getPackageName();
        for (ApplicationInfo info : apps) {
            String pkg = info.packageName;
            if (pkg.equals(myPkg) || pkg.contains("settings")) continue;
            if (!includeSystem && pm.getLaunchIntentForPackage(pkg) == null) continue;

            try {
                UUID uuid = info.storageUuid != null ? info.storageUuid : StorageManager.UUID_DEFAULT;
                StorageStats stats = ssm.queryStatsForPackage(uuid, pkg, Process.myUserHandle());
                long cacheBytes = stats.getCacheBytes();
                if (cacheBytes >= MIN_CLEARABLE_CACHE_BYTES) {
                    CharSequence labelCs = pm.getApplicationLabel(info);
                    String name = labelCs != null ? labelCs.toString() : pkg;
                    result.add(new CachedApp(name, pkg, cacheBytes));
                }
            } catch (Exception ignored) {
            }
        }

        Collections.sort(result, (a, b) -> Long.compare(b.cacheBytes, a.cacheBytes));
        return result;
    }

    private void renderScannedList() {
        listContainer.removeAllViews();

        if (scannedApps.isEmpty()) {
            listHeaderText.setText("Apps With Cache (0 found)");
            toggleSelectButton.setVisibility(View.GONE);
            TextView empty = new TextView(this);
            empty.setText("No apps with significant cache found! Tap 'Scan Apps for Cache' anytime to re-check.");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            empty.setTextColor(Color.parseColor("#6B7280"));
            empty.setPadding(dp(4), dp(8), dp(4), dp(8));
            listContainer.addView(empty);
            return;
        }

        long totalBytes = 0L;
        for (CachedApp app : scannedApps) {
            totalBytes += app.cacheBytes;
        }
        listHeaderText.setText("Found " + scannedApps.size() + " apps ("
                + Formatter.formatShortFileSize(this, totalBytes) + ")");
        toggleSelectButton.setVisibility(View.VISIBLE);

        for (int i = 0; i < scannedApps.size(); i++) {
            final CachedApp item = scannedApps.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(2), dp(6), dp(4), dp(6));

            CheckBox cb = new CheckBox(this);
            cb.setText(item.appName);
            cb.setTextColor(Color.parseColor("#111827"));
            cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            cb.setChecked(item.selected);
            cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                item.selected = isChecked;
                updateCleanButtonLabel();
            });
            row.addView(cb, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView sizeView = new TextView(this);
            sizeView.setText(Formatter.formatShortFileSize(this, item.cacheBytes));
            sizeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            sizeView.setTextColor(Color.parseColor("#2563EB"));
            sizeView.getPaint().setFakeBoldText(true);
            row.addView(sizeView);

            listContainer.addView(row);

            if (i < scannedApps.size() - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(Color.parseColor("#E5E7EB"));
                listContainer.addView(divider, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1));
            }
        }
    }

    private void toggleSelectAll() {
        boolean anyUnchecked = false;
        for (CachedApp app : scannedApps) {
            if (!app.selected) {
                anyUnchecked = true;
                break;
            }
        }
        for (CachedApp app : scannedApps) {
            app.selected = anyUnchecked;
        }
        renderScannedList();
        updateCleanButtonLabel();
    }

    private void handleStartCleaning(int limit) {
        if (!isAccessibilityEnabled() || !CacheCleanerService.isServiceConnected()) {
            Toast.makeText(this, "Turn ON 'My Cache Cleaner' in Accessibility Settings first.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }

        if (scannedApps.isEmpty()) {
            Toast.makeText(this, "Tap 'Scan Apps for Cache' first to build the list.", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> targets = new ArrayList<>();
        for (CachedApp app : scannedApps) {
            if (app.selected) {
                targets.add(app.packageName);
            }
        }

        if (targets.isEmpty()) {
            Toast.makeText(this, "Select at least one app to clean.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (limit > 0 && targets.size() > limit) {
            targets = new ArrayList<>(targets.subList(0, limit));
        }

        boolean started = CacheCleanerService.startCleaning(targets, turboCheck.isChecked());
        if (started) {
            Toast.makeText(this, "Cleaning " + targets.size() + " apps...", Toast.LENGTH_SHORT).show();
            refreshUI();
        } else {
            Toast.makeText(this, "Toggle Accessibility OFF and ON again, then retry.", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshUI() {
        boolean usageOk = hasUsageAccess();
        if (usageOk) {
            usageBadge.setText("1. Usage Access (Scanner): ON (Ready)");
            usageBadge.setTextColor(Color.parseColor("#065F46"));
            usageBadge.setBackground(roundedBox("#D1FAE5", dp(8)));
        } else {
            usageBadge.setText("1. Usage Access (Scanner): OFF — Tap button 1 below");
            usageBadge.setTextColor(Color.parseColor("#991B1B"));
            usageBadge.setBackground(roundedBox("#FEE2E2", dp(8)));
        }

        boolean accessOk = isAccessibilityEnabled() && CacheCleanerService.isServiceConnected();
        if (accessOk) {
            accessBadge.setText("2. Accessibility Service (Cleaner): ON (Ready)");
            accessBadge.setTextColor(Color.parseColor("#065F46"));
            accessBadge.setBackground(roundedBox("#D1FAE5", dp(8)));
        } else {
            accessBadge.setText("2. Accessibility Service (Cleaner): OFF — Tap button 2 below");
            accessBadge.setTextColor(Color.parseColor("#991B1B"));
            accessBadge.setBackground(roundedBox("#FEE2E2", dp(8)));
        }

        boolean running = CacheCleanerService.isRunning();
        scanButton.setEnabled(!running && !isScanning);
        scanButton.setText(isScanning ? "Scanning apps..." : "Scan Apps for Cache");

        updateCleanButtonLabel();
        testButton.setEnabled(!running && !scannedApps.isEmpty());
        testButton.setAlpha(testButton.isEnabled() ? 1.0f : 0.45f);
        stopButton.setEnabled(running);
        stopButton.setAlpha(running ? 1.0f : 0.45f);

        String debug = CacheCleanerService.getLastDebug();
        String status = "Status: " + CacheCleanerService.getLastSummary();
        if (debug != null && !debug.isEmpty()) {
            status += "\n\nDiagnostic: " + debug;
        }
        summaryText.setText(status);
    }

    private void updateCleanButtonLabel() {
        int selectedCount = 0;
        long selectedBytes = 0L;
        for (CachedApp app : scannedApps) {
            if (app.selected) {
                selectedCount++;
                selectedBytes += app.cacheBytes;
            }
        }
        boolean running = CacheCleanerService.isRunning();
        if (selectedCount == 0) {
            cleanButton.setText("Turbo Clean Selected (Scan First)");
            cleanButton.setEnabled(false);
            cleanButton.setAlpha(0.5f);
        } else {
            String prefix = turboCheck.isChecked() ? "Turbo Clean " : "Clean ";
            cleanButton.setText(prefix + selectedCount + " Apps ("
                    + Formatter.formatShortFileSize(this, selectedBytes) + ")");
            cleanButton.setEnabled(!running);
            cleanButton.setAlpha(running ? 0.5f : 1.0f);
        }
    }

    private boolean hasUsageAccess() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) return false;
        int mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                getPackageName());
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
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

    private Button makeButton(String label, String hexColor, int textSp) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        btn.setPadding(dp(14), dp(12), dp(14), dp(12));
        btn.setBackground(roundedBox(hexColor, dp(10)));
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

    private LinearLayout.LayoutParams weightParams(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
