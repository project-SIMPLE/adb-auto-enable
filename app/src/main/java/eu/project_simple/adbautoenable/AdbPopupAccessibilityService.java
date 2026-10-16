package eu.project_simple.adbautoenable;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * Event-driven Accessibility Service for Meta Quest.
 * Clicks "Always allow on this network" on the VrUsb wireless debugging popup.
 *
 * Key injection (`input keyevent`) can't be used: Android rejects events injected by an app
 * into windows it doesn't own. Gestures can't be used either: canPerformGestures makes the
 * system route all input through an accessibility InputFilter, which breaks Horizon OS input
 * (see issue #2). So we only use node actions on the popup's own buttons.
 */
public class AdbPopupAccessibilityService extends AccessibilityService {
    private static final String TAG = "ADBAutoEnable.A11y";
    private static final String POPUP_WINDOW_TITLE = "VrUsb";
    // AlertDialog neutral button = "Always allow on this network"
    private static final String ALWAYS_ALLOW_BUTTON_ID = "android:id/button3";
    // The popup window shows up before its content is reachable, so retry for a little while
    private static final long RETRY_DELAY_MS = 250;
    private static final int MAX_RETRIES = 20;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int retriesLeft = 0;

    private final Runnable retryTask = new Runnable() {
        @Override
        public void run() {
            if (scanAndClick() != ScanResult.NOT_READY) return;
            if (--retriesLeft > 0) {
                handler.postDelayed(this, RETRY_DELAY_MS);
            } else {
                Log.w(TAG, "VrUsb popup content never became reachable, giving up");
            }
        }
    };

    private enum ScanResult { NO_POPUP, NOT_READY, CLICKED }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Only scan when windows change or state changes
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            scan();
        }
    }

    private void scan() {
        handler.removeCallbacks(retryTask);
        retriesLeft = MAX_RETRIES;
        retryTask.run();
    }

    private ScanResult scanAndClick() {
        SparseArray<List<AccessibilityWindowInfo>> byDisplay = getWindowsOnAllDisplays();
        if (byDisplay == null) return ScanResult.NO_POPUP;

        for (int i = 0; i < byDisplay.size(); i++) {
            List<AccessibilityWindowInfo> windows = byDisplay.valueAt(i);
            if (windows == null) continue;
            for (AccessibilityWindowInfo w : windows) {
                CharSequence title = w.getTitle();
                if (title != null && title.toString().contains(POPUP_WINDOW_TITLE)) {
                    return clickAlwaysAllow(w);
                }
            }
        }
        return ScanResult.NO_POPUP;
    }

    private ScanResult clickAlwaysAllow(AccessibilityWindowInfo window) {
        AccessibilityNodeInfo root = window.getRoot();
        if (root == null) return ScanResult.NOT_READY;

        List<AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByViewId(ALWAYS_ALLOW_BUTTON_ID);
        if (buttons == null || buttons.isEmpty()) return ScanResult.NOT_READY;

        AccessibilityNodeInfo button = buttons.get(0);
        if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.i(TAG, "VrUsb popup detected, clicked '" + button.getText() + "'");
            return ScanResult.CLICKED;
        }
        return ScanResult.NOT_READY;
    }

    @Override public void onInterrupt() {}

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Hook Connected");
        scan(); // Initial check
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(retryTask);
        super.onDestroy();
    }
}
