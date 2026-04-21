package com.tpn.adbautoenable;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

public class AdbPopupAccessibilityService extends AccessibilityService {
    private static final String TAG = "ADBAutoEnable.A11y";
    private static final String NEUTRAL_BUTTON_ID = "android:id/button3";
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
        // Ignored — we rely on the periodic scan. Meta's VR shell does not
        // route WindowStateChanged events for VrUsb to a11y clients.
    }

    private void scanAndClick() {
        SparseArray<List<AccessibilityWindowInfo>> byDisplay = getWindowsOnAllDisplays();
        if (byDisplay == null || byDisplay.size() == 0) {
            // Optional: Log.v(TAG, "scan: no windows");
            return;
        }
        for (int i = 0; i < byDisplay.size(); i++) {
            int displayId = byDisplay.keyAt(i);
            List<AccessibilityWindowInfo> windows = byDisplay.valueAt(i);
            if (windows == null) continue;
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;

                CharSequence pkgName = root.getPackageName();
                String pkgStr = pkgName == null ? "" : pkgName.toString();
                CharSequence title = w.getTitle();
                String titleStr = title == null ? "" : title.toString();

                // Log all windows we see to help debug
                Log.v(TAG, "scan: display=" + displayId + " pkg=" + pkgStr + " title=" + titleStr);

                // Check for vrusb, debugging, or alert in package name or title
                boolean isTarget = pkgStr.toLowerCase().contains("vrusb") || 
                                   pkgStr.toLowerCase().contains("com.oculus.os") ||
                                   titleStr.toLowerCase().contains("debugging") ||
                                   titleStr.toLowerCase().contains("alert");

                if (!isTarget) {
                    root.recycle();
                    continue;
                }

                Log.i(TAG, "scan: found POTENTIAL target window! pkg=" + pkgStr + " title=" + titleStr + " display=" + displayId);
                
                boolean clicked = false;
                
                // 1. Try "Always allow" checkbox/button
                if (clickByText(root, "Always allow on this network")) clicked = true;
                if (clickByText(root, "Always allow")) clicked = true;

                // 2. Try the main action buttons (Neutral is often used for "Always allow" in standard Android)
                // In some Meta versions, they might use different IDs or just text.
                if (clickById(root, "android:id/button3")) clicked = true; // Neutral
                if (clickById(root, "android:id/button1")) clicked = true; // Positive ("Allow")
                
                // 3. Fallback to text matching if IDs didn't work
                if (!clicked) {
                    if (clickByText(root, "Allow")) clicked = true;
                    if (clickByText(root, "OK")) clicked = true;
                }
                
                if (clicked) {
                    Log.i(TAG, "scan: successfully performed click(s) in window");
                    root.recycle();
                    return;
                }

                // If we are sure it's the vrusb window but we couldn't click anything, dump it
                if (pkgStr.contains("vrusb")) {
                    Log.w(TAG, "scan: VrUsb window found but no buttons clicked. Dumping...");
                    dumpNodes(root, 0);
                }
                root.recycle();
            }
        }
    }

    private void dumpNodes(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 10) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append("Node: id=").append(node.getViewIdResourceName())
          .append(" text=").append(node.getText())
          .append(" class=").append(node.getClassName())
          .append(" clickable=").append(node.isClickable());
        Log.d(TAG, sb.toString());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            dumpNodes(child, depth + 1);
            if (child != null) child.recycle();
        }
    }

    private boolean clickById(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
        if (nodes == null) return false;
        boolean found = false;
        for (AccessibilityNodeInfo node : nodes) {
            if (!found && performClick(node)) {
                Log.i(TAG, "clicked node by id: " + id);
                found = true;
            }
            node.recycle();
        }
        return found;
    }

    private boolean clickByText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null) return false;
        boolean found = false;
        for (AccessibilityNodeInfo node : nodes) {
            if (!found && performClick(node)) {
                Log.i(TAG, "clicked node by text: " + text);
                found = true;
            }
            node.recycle();
        }
        return found;
    }

    private boolean performClick(AccessibilityNodeInfo node) {
        if (node == null) return false;
        AccessibilityNodeInfo cur = node;
        // We don't recycle 'node' here because the caller (clickById/Text) does it.
        // But we must recycle parents we traverse.
        for (int depth = 0; depth < 5 && cur != null; depth++) {
            if (cur.isClickable()) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    if (cur != node) cur.recycle();
                    return true;
                }
            }
            AccessibilityNodeInfo parent = cur.getParent();
            if (cur != node) cur.recycle();
            cur = parent;
        }
        return false;
    }

    @Override
    public void onInterrupt() {
        // no-op
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Accessibility service connected — starting periodic scan");
        handler.removeCallbacks(scanRunnable);
        handler.post(scanRunnable);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        handler.removeCallbacks(scanRunnable);
        return super.onUnbind(intent);
    }
}
