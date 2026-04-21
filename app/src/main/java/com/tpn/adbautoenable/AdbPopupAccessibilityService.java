package com.tpn.adbautoenable;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.KeyEvent;

import java.util.List;

/**
 * Accessibility Service that specifically targets the Meta Quest Wireless ADB trust popup.
 * Since Meta's system windows often report a NULL root to A11y services, this service
 * uses a 'blind' keyboard navigation sequence to click the "Always allow" button.
 */
public class AdbPopupAccessibilityService extends AccessibilityService {
    private static final String TAG = "ADBAutoEnable.A11y";
    private static final long SCAN_INTERVAL_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            scanAndClick();
            handler.postDelayed(this, SCAN_INTERVAL_MS);
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Trigger an immediate scan on any window or state change
        handler.removeCallbacks(scanRunnable);
        handler.post(scanRunnable);
    }

    private void scanAndClick() {
        SparseArray<List<AccessibilityWindowInfo>> byDisplay = getWindowsOnAllDisplays();
        if (byDisplay == null) return;

        for (int i = 0; i < byDisplay.size(); i++) {
            List<AccessibilityWindowInfo> windows = byDisplay.valueAt(i);
            if (windows == null) continue;
            for (AccessibilityWindowInfo w : windows) {
                CharSequence title = w.getTitle();
                if (title != null && title.toString().contains("VrUsb")) {
                    Log.i(TAG, "VrUsb popup detected. Executing blind trust sequence.");
                    performBlindSequence();
                    return; 
                }
            }
        }
    }

    /**
     * Executes a keyboard sequence to navigate the standard AlertActivity:
     * [Allow] -> TAB -> [Cancel] -> TAB -> [Always Allow] -> ENTER
     */
    private void performBlindSequence() {
        Log.d(TAG, "Sequence: 3x TAB, 1x ENTER");
        
        sendKey(KeyEvent.KEYCODE_TAB);
        handler.postDelayed(() -> sendKey(KeyEvent.KEYCODE_TAB), 150);
        handler.postDelayed(() -> sendKey(KeyEvent.KEYCODE_TAB), 300);
        handler.postDelayed(() -> sendKey(KeyEvent.KEYCODE_ENTER), 450);
    }

    private void sendKey(int keyCode) {
        try {
            // We use 'input keyevent' which is reliable when WRITE_SECURE_SETTINGS is granted
            String cmd = "input keyevent " + keyCode;
            Runtime.getRuntime().exec(cmd);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send key: " + keyCode, e);
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Service Connected");
        handler.post(scanRunnable);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        handler.removeCallbacks(scanRunnable);
        return super.onUnbind(intent);
    }
}
