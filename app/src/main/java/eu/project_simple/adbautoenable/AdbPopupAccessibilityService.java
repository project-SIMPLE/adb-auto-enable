package eu.project_simple.adbautoenable;

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
 * Event-driven Accessibility Service for Meta Quest.
 * Blindly navigates the VrUsb trust popup using keyboard events.
 */
public class AdbPopupAccessibilityService extends AccessibilityService {
    private static final String TAG = "ADBAutoEnable.A11y";
    private static final long COOLDOWN_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastActionTime = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Only scan when windows change or state changes
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            scanAndClick();
        }
    }

    private void scanAndClick() {
        if (System.currentTimeMillis() - lastActionTime < COOLDOWN_MS) return;

        SparseArray<List<AccessibilityWindowInfo>> byDisplay = getWindowsOnAllDisplays();
        if (byDisplay == null) return;

        for (int i = 0; i < byDisplay.size(); i++) {
            List<AccessibilityWindowInfo> windows = byDisplay.valueAt(i);
            if (windows == null) continue;
            for (AccessibilityWindowInfo w : windows) {
                CharSequence title = w.getTitle();
                if (title != null && title.toString().contains("VrUsb")) {
                    Log.i(TAG, "VrUsb detected. Triggering trust sequence.");
                    performBlindSequence();
                    return; 
                }
            }
        }
    }

    private void performBlindSequence() {
        lastActionTime = System.currentTimeMillis();
        Log.d(TAG, "TAB x3, ENTER");
        
        sendKey(KeyEvent.KEYCODE_TAB);
        handler.postDelayed(() -> sendKey(KeyEvent.KEYCODE_TAB), 100);
        handler.postDelayed(() -> sendKey(KeyEvent.KEYCODE_TAB), 200);
        handler.postDelayed(() -> sendKey(KeyEvent.KEYCODE_ENTER), 300);
    }

    private void sendKey(int keyCode) {
        try {
            Runtime.getRuntime().exec("input keyevent " + keyCode);
        } catch (Exception ignored) {}
    }

    @Override public void onInterrupt() {}

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Hook Connected");
        scanAndClick(); // Initial check
    }
}
