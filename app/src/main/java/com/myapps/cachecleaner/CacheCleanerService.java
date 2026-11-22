package com.myapps.cachecleaner;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CacheCleanerService extends AccessibilityService {

    private static final int STATE_IDLE = 0;
    private static final int STATE_WAIT_STORAGE = 1;
    private static final int STATE_WAIT_CLEAR = 2;
    private static final int STATE_MOVING_NEXT = 3;

    private static CacheCleanerService instance;
    private static volatile boolean running = false;
    private static volatile boolean justFinished = false;
    private static volatile String lastSummary = "Ready. Tap 'Scan Apps for Cache' to begin.";
    private static volatile String lastDebug = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> queue = new ArrayList<>();

    private boolean turboMode = true;
    private int state = STATE_IDLE;
    private int currentIndex = -1;
    private int cleanedCount = 0;
    private int alreadyEmptyCount = 0;
    private int skippedCount = 0;
    private int totalCount = 0;
    private long startTimeMs = 0L;
    private long ignoreUntil = 0L;
    private int scrollAttempts = 0;
    private int clearScrollAttempts = 0;
    private boolean recheckScheduled = false;

    private final Runnable watchdogRunnable = () -> {
        if (!running) return;
        AccessibilityNodeInfo root = getBestSettingsRoot();
        captureDebug(root, state == STATE_WAIT_STORAGE ? "WAIT_STORAGE_TIMEOUT" : "WAIT_CLEAR_TIMEOUT");
        skippedCount++;
        state = STATE_MOVING_NEXT;
        openNextApp();
    };

    private final Runnable pollRunnable = () -> {
        if (!running) return;
        if (SystemClock.uptimeMillis() < ignoreUntil) return;
        AccessibilityNodeInfo root = getBestSettingsRoot();
        if (root != null) {
            processScreen(root);
        }
    };

    private final Runnable recheckClearRunnable = () -> {
        if (!running || state != STATE_WAIT_CLEAR) return;
        AccessibilityNodeInfo root = getBestSettingsRoot();
        if (root != null) {
            if (containsComputing(root)) {
                recheckScheduled = false;
                handler.postDelayed(pollRunnable, turboMode ? 240 : 400);
                return;
            }
            AccessibilityNodeInfo clearNode = findClearCacheNode(root);
            if (clearNode != null) {
                AccessibilityNodeInfo clickable = findClickableTarget(clearNode);
                if (clickable != null && clickable.isEnabled() && clearNode.isEnabled()) {
                    if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        state = STATE_MOVING_NEXT;
                        cleanedCount++;
                        handler.removeCallbacksAndMessages(null);
                        handler.postDelayed(this::openNextApp, turboMode ? 150 : 320);
                        return;
                    }
                } else {
                    state = STATE_MOVING_NEXT;
                    alreadyEmptyCount++;
                    handler.removeCallbacksAndMessages(null);
                    handler.postDelayed(this::openNextApp, turboMode ? 70 : 150);
                    return;
                }
            }
        }
        captureDebug(root, "CLEAR_RECHECK_SKIP");
        skippedCount++;
        state = STATE_MOVING_NEXT;
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::openNextApp, turboMode ? 70 : 150);
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onInterrupt() {
        stopInternal("Cleaning interrupted.");
    }

    @Override
    public void onDestroy() {
        stopInternal("Service stopped.");
        instance = null;
        super.onDestroy();
    }

    public static boolean isServiceConnected() {
        return instance != null;
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean consumeJustFinished() {
        if (justFinished) {
            justFinished = false;
            return true;
        }
        return false;
    }

    public static String getLastSummary() {
        return lastSummary;
    }

    public static String getLastDebug() {
        return lastDebug;
    }

    public static boolean startCleaning(List<String> packages, boolean turbo) {
        if (instance == null || packages == null || packages.isEmpty()) {
            return false;
        }
        instance.beginQueue(packages, turbo);
        return true;
    }

    public static void stopCleaning() {
        if (instance != null) {
            instance.stopInternal("Stopped by user.");
        } else {
            running = false;
        }
    }

    private void beginQueue(List<String> packages, boolean turbo) {
        handler.removeCallbacksAndMessages(null);
        queue.clear();
        queue.addAll(packages);
        turboMode = turbo;
        currentIndex = -1;
        cleanedCount = 0;
        alreadyEmptyCount = 0;
        skippedCount = 0;
        totalCount = queue.size();
        startTimeMs = SystemClock.uptimeMillis();
        justFinished = false;
        lastDebug = "";
        running = true;
        lastSummary = (turboMode ? "Turbo cleaning " : "Cleaning ") + totalCount + " apps...";
        openNextApp();
    }

    private void stopInternal(String reason) {
        running = false;
        state = STATE_IDLE;
        handler.removeCallbacksAndMessages(null);
        long secs = startTimeMs > 0 ? Math.max(1, (SystemClock.uptimeMillis() - startTimeMs) / 1000L) : 0;
        lastSummary = reason + " (" + secs + "s) Cleared: " + cleanedCount
                + " | Empty: " + alreadyEmptyCount
                + " | Skipped: " + skippedCount + " (of " + totalCount + ")";
        justFinished = true;
    }

    private void openNextApp() {
        handler.removeCallbacksAndMessages(null);
        if (!running) return;

        currentIndex++;
        if (currentIndex >= queue.size()) {
            finishCleaning();
            return;
        }

        String pkg = queue.get(currentIndex);
        state = STATE_WAIT_STORAGE;
        scrollAttempts = 0;
        clearScrollAttempts = 0;
        recheckScheduled = false;
        ignoreUntil = SystemClock.uptimeMillis() + (turboMode ? 140 : 320);

        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + pkg));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);

        try {
            startActivity(intent);
        } catch (Exception e) {
            skippedCount++;
            handler.postDelayed(this::openNextApp, 80);
            return;
        }

        handler.postDelayed(watchdogRunnable, turboMode ? 2200 : 3400);
        handler.postDelayed(pollRunnable, turboMode ? 220 : 420);
        handler.postDelayed(pollRunnable, turboMode ? 520 : 900);
        handler.postDelayed(pollRunnable, turboMode ? 1050 : 1600);
    }

    private void finishCleaning() {
        running = false;
        state = STATE_IDLE;
        handler.removeCallbacksAndMessages(null);
        long secs = Math.max(1, (SystemClock.uptimeMillis() - startTimeMs) / 1000L);
        lastSummary = "Done in " + secs + "s! Cleared: " + cleanedCount
                + " | Empty: " + alreadyEmptyCount
                + " | Skipped: " + skippedCount + " (of " + totalCount + ")";
        justFinished = true;
        Toast.makeText(this, lastSummary, Toast.LENGTH_LONG).show();

        Intent back = new Intent(this, MainActivity.class);
        back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(back);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!running) return;
        if (SystemClock.uptimeMillis() < ignoreUntil) return;

        AccessibilityNodeInfo root = getBestSettingsRoot();
        if (root != null) {
            processScreen(root);
        }
    }

    private AccessibilityNodeInfo getBestSettingsRoot() {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (isSettingsRoot(active)) {
            return active;
        }
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w != null && w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        AccessibilityNodeInfo r = w.getRoot();
                        if (isSettingsRoot(r)) {
                            return r;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return active;
    }

    private boolean isSettingsRoot(AccessibilityNodeInfo root) {
        if (root == null) return false;
        CharSequence pkgCs = root.getPackageName();
        if (pkgCs == null) return false;
        String pkg = pkgCs.toString().toLowerCase(Locale.ROOT);
        if (pkg.equals(getPackageName().toLowerCase(Locale.ROOT))) return false;
        return pkg.contains("settings");
    }

    private void processScreen(AccessibilityNodeInfo root) {
        if (!running || root == null) return;
        if (!isSettingsRoot(root)) return;

        if (state == STATE_WAIT_STORAGE) {
            if (findClearCacheNode(root) != null) {
                return;
            }

            AccessibilityNodeInfo storageNode = findStorageNode(root);
            if (storageNode != null) {
                AccessibilityNodeInfo clickable = findClickableTarget(storageNode);
                if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    state = STATE_WAIT_CLEAR;
                    clearScrollAttempts = 0;
                    recheckScheduled = false;
                    ignoreUntil = SystemClock.uptimeMillis() + (turboMode ? 120 : 280);
                    handler.removeCallbacks(pollRunnable);
                    handler.postDelayed(pollRunnable, turboMode ? 220 : 400);
                    handler.postDelayed(pollRunnable, turboMode ? 550 : 900);
                    handler.postDelayed(pollRunnable, turboMode ? 1050 : 1500);
                }
            } else if (scrollAttempts < 2) {
                scrollAttempts++;
                ignoreUntil = SystemClock.uptimeMillis() + (turboMode ? 150 : 280);
                scrollListForward(root);
                handler.postDelayed(pollRunnable, turboMode ? 220 : 360);
            }
        } else if (state == STATE_WAIT_CLEAR) {
            AccessibilityNodeInfo clearNode = findClearCacheNode(root);
            if (clearNode != null) {
                handleClearScreen(root, clearNode);
            } else if (clearScrollAttempts < 1 && containsText(root, "space used")) {
                clearScrollAttempts++;
                ignoreUntil = SystemClock.uptimeMillis() + (turboMode ? 160 : 280);
                scrollListForward(root);
                handler.postDelayed(pollRunnable, turboMode ? 220 : 360);
            }
        }
    }

    private void handleClearScreen(AccessibilityNodeInfo root, AccessibilityNodeInfo clearNode) {
        if (containsComputing(root)) {
            return;
        }
        AccessibilityNodeInfo clickable = findClickableTarget(clearNode);
        boolean enabled = clickable != null && clickable.isEnabled() && clearNode.isEnabled();

        if (enabled) {
            if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                state = STATE_MOVING_NEXT;
                cleanedCount++;
                handler.removeCallbacksAndMessages(null);
                handler.postDelayed(this::openNextApp, turboMode ? 150 : 320);
            }
        } else if (!recheckScheduled) {
            recheckScheduled = true;
            handler.postDelayed(recheckClearRunnable, turboMode ? 350 : 550);
        }
    }

    private AccessibilityNodeInfo findStorageNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String text = normalize(node.getText());
        String desc = normalize(node.getContentDescription());
        if (isStorageLabel(text) || isStorageLabel(desc)) {
            return node;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo found = findStorageNode(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private boolean isStorageLabel(String s) {
        if (s.isEmpty() || s.contains("clear")) return false;
        return s.equals("storage & cache")
                || s.equals("storage and cache")
                || s.equals("storage usage")
                || s.equals("app storage")
                || s.equals("storage")
                || s.startsWith("storage &")
                || s.startsWith("storage and");
    }

    private AccessibilityNodeInfo findClearCacheNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String text = normalize(node.getText());
        String desc = normalize(node.getContentDescription());
        if (isClearCacheLabel(text) || isClearCacheLabel(desc)) {
            return node;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo found = findClearCacheNode(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private boolean isClearCacheLabel(String s) {
        if (s.isEmpty()) return false;
        if (s.contains("storage") || s.contains("data")) return false;
        return s.equals("clear cache");
    }

    private boolean containsComputing(AccessibilityNodeInfo node) {
        if (node == null) return false;
        String text = normalize(node.getText());
        if (text.contains("computing") || text.contains("calculating")) {
            return true;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            if (containsComputing(node.getChild(i))) return true;
        }
        return false;
    }

    private boolean containsText(AccessibilityNodeInfo node, String needleLower) {
        if (node == null) return false;
        if (normalize(node.getText()).contains(needleLower)) {
            return true;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            if (containsText(node.getChild(i), needleLower)) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findClickableTarget(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isClickable()) return current;
            current = current.getParent();
        }
        return null;
    }

    private boolean scrollListForward(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo list = findScrollableList(root, true);
        if (list == null) {
            list = findScrollableList(root, false);
        }
        return list != null && list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }

    private AccessibilityNodeInfo findScrollableList(AccessibilityNodeInfo node, boolean preferList) {
        if (node == null) return null;
        if (node.isScrollable()) {
            CharSequence cls = node.getClassName();
            String clsStr = cls == null ? "" : cls.toString().toLowerCase(Locale.ROOT);
            if (!preferList || clsStr.contains("recycler") || clsStr.contains("list") || clsStr.contains("scroll")) {
                return node;
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo found = findScrollableList(node.getChild(i), preferList);
            if (found != null) return found;
        }
        return null;
    }

    private void captureDebug(AccessibilityNodeInfo root, String tag) {
        if (root == null) {
            lastDebug = tag + " | active screen: null";
            return;
        }
        CharSequence pkgCs = root.getPackageName();
        String pkg = pkgCs == null ? "unknown" : pkgCs.toString();
        List<String> samples = new ArrayList<>();
        collectSampleTexts(root, samples, 25);
        lastDebug = tag + " | pkg=" + pkg + " | texts=" + samples;
    }

    private void collectSampleTexts(AccessibilityNodeInfo node, List<String> out, int limit) {
        if (node == null || out.size() >= limit) return;
        String t = normalize(node.getText());
        if (!t.isEmpty() && !out.contains(t)) {
            out.add(t);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count && out.size() < limit; i++) {
            collectSampleTexts(node.getChild(i), out, limit);
        }
    }

    private String normalize(CharSequence cs) {
        if (cs == null) return "";
        return cs.toString()
                .replace('\n', ' ')
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
