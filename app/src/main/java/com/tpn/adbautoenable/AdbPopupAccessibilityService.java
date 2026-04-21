package com.tpn.adbautoenable;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.KeyEvent;

import java.util.List;

public class AdbPopupAccessibilityService extends AccessibilityService {
    private static final String TAG = "ADBAutoEnable.A11y";
    private static final long SCAN_INTERVAL_MS = 500;

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
        handler.removeCallbacks(scanRunnable);
        handler.post(scanRunnable);
    }

    private void scanAndClick() {
        SparseArray<List<AccessibilityWindowInfo>> byDisplay = getWindowsOnAllDisplays();
        if (byDisplay != null) {
            for (int i = 0; i < byDisplay.size(); i++) {
                List<AccessibilityWindowInfo> windows = byDisplay.valueAt(i);
                if (windows != null) {
                    for (AccessibilityWindowInfo w : windows) {
                        CharSequence title = w.getTitle();
                        if (title != null && title.toString().contains("VrUsb")) {
                            Log.i(TAG, "VrUsb window detected (title match). Root is " + (w.getRoot() == null ? "NULL" : "OK"));
                            // If root is null, we can't see nodes, but we might be able to send keys!
                            performBlindSequence();
                            return; 
                        }
                    }
                }
            }
        }
    }

    private void performBlindSequence() {
        Log.i(TAG, "Attempting blind keyboard sequence: TAB, TAB, TAB, ENTER");
        
        // This targets the 3rd button (Neutral) in a standard AlertActivity
        // sequence: [Allow] -> TAB -> [Cancel] -> TAB -> [Always Allow] -> ENTER
        
        sendKey(KeyEvent.KEYCODE_TAB);
        handler.postDelayed(() -> sendKey(KeyEvent.KEYCODE_TAB), 100);
        handler.postDelayed(() -> sendKey(KeyEvent.KEYCODE_TAB), 200);
        handler.postDelayed(() -> sendKey(KeyEvent.KEYCODE_ENTER), 300);
    }

    private void sendKey(int keyCode) {
        // AccessibilityService can't inject keys via Instrumentation, but it can use GlobalActions 
        // for some things. For actual keys, we'd need another way or just hope focus works.
        // Wait, standard A11y services CANNOT send key events to other apps without specific flags.
        // Let's use the 'input keyevent' command since we have WRITE_SECURE_SETTINGS (maybe we can run shell?)
        try {
            String cmd = "input keyevent " + keyCode;
            Runtime.getRuntime().exec(cmd);
            Log.d(TAG, "Sent key: " + keyCode);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send key: " + keyCode, e);
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Service Connected");
        handler.removeCallbacks(scanRunnable);
        handler.post(scanRunnable);
    }
}
