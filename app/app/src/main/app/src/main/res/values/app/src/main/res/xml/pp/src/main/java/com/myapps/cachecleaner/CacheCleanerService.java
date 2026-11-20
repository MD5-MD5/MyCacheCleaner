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
    private static volatile String lastSummary = "Ready to clean.";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> queue = new ArrayList<>();

    private int state = STATE_IDLE;
    private int currentIndex = -1;
    private int cleanedCount = 0;
    private int totalCount = 0;
    private long ignoreUntil = 0L;
    private boolean scrolledOnce = false;
    private boolean recheckScheduled = false;

    private final Runnable watchdogRunnable = () -> {
        if (!running) return;
        state = STATE_MOVING_NEXT;
        openNextApp();
    };

    private final Runnable recheckRunnable = () -> {
        if (!running || state != STATE_WAIT_CLEAR) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            if (containsText(root, "computing")) {
                recheckScheduled = false;
                return;
            }
            AccessibilityNodeInfo clearNode = findNodeByExactText(root, "clear cache");
            if (clearNode != null) {
                AccessibilityNodeInfo clickable = findClickableParent(clearNode);
                if (clearNode.isEnabled() && clickable != null && clickable.isEnabled()) {
                    state = STATE_MOVING_NEXT;
                    handler.removeCallbacks(watchdogRunnable);
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    cleanedCount++;
                    handler.postDelayed(this::openNextApp, 400);
                    return;
                }
            }
        }
        state = STATE_MOVING_NEXT;
        handler.removeCallbacks(watchdogRunnable);
        handler.postDelayed(this::openNextApp, 200);
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

    public static String getLastSummary() {
        return lastSummary;
    }

    public static boolean startCleaning(List<String> packages) {
        if (instance == null || packages == null || packages.isEmpty()) {
            return false;
        }
        instance.beginQueue(packages);
        return true;
    }

    public static void stopCleaning() {
        if (instance != null) {
            instance.stopInternal("Cleaning stopped by user.");
        } else {
            running = false;
        }
    }

    private void beginQueue(List<String> packages) {
        handler.removeCallbacksAndMessages(null);
        queue.clear();
        queue.addAll(packages);
        currentIndex = -1;
        cleanedCount = 0;
        totalCount = queue.size();
        running = true;
        lastSummary = "Cleaning " + totalCount + " apps...";
        openNextApp();
    }

    private void stopInternal(String reason) {
        running = false;
        state = STATE_IDLE;
        handler.removeCallbacksAndMessages(null);
        lastSummary = reason + " Cleared " + cleanedCount + " of " + totalCount + " apps.";
    }

    private void openNextApp() {
        handler.removeCallbacks(watchdogRunnable);
        handler.removeCallbacks(recheckRunnable);
        if (!running) return;

        currentIndex++;
        if (currentIndex >= queue.size()) {
            finishCleaning();
            return;
        }

        String pkg = queue.get(currentIndex);
        state = STATE_WAIT_STORAGE;
        scrolledOnce = false;
        recheckScheduled = false;
        ignoreUntil = SystemClock.uptimeMillis() + 450;

        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + pkg));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_HISTORY);

        try {
            startActivity(intent);
        } catch (Exception e) {
            handler.postDelayed(this::openNextApp, 150);
            return;
        }

        handler.postDelayed(watchdogRunnable, 4500);
        handler.postDelayed(() -> {
            if (running && state == STATE_WAIT_STORAGE) {
                AccessibilityNodeInfo r = getRootInActiveWindow();
                if (r != null) processScreen(r);
            }
        }, 650);
    }

    private void finishCleaning() {
        running = false;
        state = STATE_IDLE;
        handler.removeCallbacksAndMessages(null);
        lastSummary = "Done! Cleared cache for " + cleanedCount + " of " + totalCount + " apps.";
        Toast.makeText(this, lastSummary, Toast.LENGTH_LONG).show();

        Intent back = new Intent(this, MainActivity.class);
        back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(back);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!running) return;
        if (SystemClock.uptimeMillis() < ignoreUntil) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        processScreen(root);
    }

    private void processScreen(AccessibilityNodeInfo root) {
        if (!running) return;

        if (state == STATE_WAIT_STORAGE) {
            if (findNodeByExactText(root, "clear cache") != null) {
                return;
            }
            AccessibilityNodeInfo storageNode = findStorageNode(root);
            if (storageNode != null) {
                AccessibilityNodeInfo clickable = findClickableParent(storageNode);
                if (clickable != null) {
                    state = STATE_WAIT_CLEAR;
                    recheckScheduled = false;
                    ignoreUntil = SystemClock.uptimeMillis() + 400;
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    handler.postDelayed(() -> {
                        if (running && state == STATE_WAIT_CLEAR) {
                            AccessibilityNodeInfo r = getRootInActiveWindow();
                            if (r != null) processScreen(r);
                        }
                    }, 550);
                }
            } else if (!scrolledOnce) {
                scrolledOnce = true;
                ignoreUntil = SystemClock.uptimeMillis() + 350;
                scrollForward(root);
            }
        } else if (state == STATE_WAIT_CLEAR) {
            AccessibilityNodeInfo clearNode = findNodeByExactText(root, "clear cache");
            if (clearNode == null) return;

            if (containsText(root, "computing")) {
                return;
            }

            AccessibilityNodeInfo clickable = findClickableParent(clearNode);
            boolean enabled = clearNode.isEnabled() && clickable != null && clickable.isEnabled();

            if (enabled) {
                state = STATE_MOVING_NEXT;
                handler.removeCallbacks(recheckRunnable);
                handler.removeCallbacks(watchdogRunnable);
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                cleanedCount++;
                handler.postDelayed(this::openNextApp, 400);
            } else if (!recheckScheduled) {
                recheckScheduled = true;
                handler.postDelayed(recheckRunnable, 650);
            }
        }
    }

    private AccessibilityNodeInfo findStorageNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String text = getNormalizedText(node);
        if (text.equals("storage & cache") || text.equals("storage and cache") || text.equals("storage")) {
            return node;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo found = findStorageNode(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findNodeByExactText(AccessibilityNodeInfo node, String targetLower) {
        if (node == null) return null;
        if (getNormalizedText(node).equals(targetLower) || getNormalizedDesc(node).equals(targetLower)) {
            return node;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo found = findNodeByExactText(node.getChild(i), targetLower);
            if (found != null) return found;
        }
        return null;
    }

    private boolean containsText(AccessibilityNodeInfo node, String needleLower) {
        if (node == null) return false;
        if (getNormalizedText(node).contains(needleLower)) {
            return true;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            if (containsText(node.getChild(i), needleLower)) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isClickable()) return current;
            current = current.getParent();
        }
        return null;
    }

    private boolean scrollForward(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isScrollable()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            if (scrollForward(node.getChild(i))) return true;
        }
        return false;
    }

    private String getNormalizedText(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        return text == null ? "" : text.toString().trim().toLowerCase(Locale.ROOT);
    }

    private String getNormalizedDesc(AccessibilityNodeInfo node) {
        CharSequence desc = node.getContentDescription();
        return desc == null ? "" : desc.toString().trim().toLowerCase(Locale.ROOT);
    }
}
