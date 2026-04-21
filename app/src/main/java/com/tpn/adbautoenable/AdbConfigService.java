package com.tpn.adbautoenable;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

/**
 * Reactive Watchdog Service.
 * Uses Network and Settings hooks to enforce ADB Wireless enablement.
 */
public class AdbConfigService extends Service {
    private static final String TAG = "ADBAutoEnable";
    private static final String CHANNEL_ID = "ADBAutoEnableChannel";
    private static final long DEBOUNCE_MS = 500; 
    private static final long WATCHDOG_INTERVAL_MS = 60_000; // 1 minute fallback

    private long lastActionTime = 0;
    private final Handler handler = new Handler();
    private ContentObserver adbObserver;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final Runnable watchdogTask = new Runnable() {
        @Override
        public void run() {
            triggerHook("Periodic Watchdog");
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service Starting");
        startForeground(1, createNotification());
        
        ensureA11yEnabled();
        registerHooks();
        
        // Start periodic fallback
        handler.postDelayed(watchdogTask, WATCHDOG_INTERVAL_MS);
        
        // Immediate check
        triggerHook("Service Start");
    }

    private void registerHooks() {
        // Hook 1: Settings Change
        adbObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                triggerHook("Setting Change");
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor("adb_wifi_enabled"), false, adbObserver);

        // Hook 2: Network Status
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    triggerHook("Network Available");
                }
                
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities cap) {
                    // NET_CAPABILITY_VALIDATED is a strong indicator that system is ready
                    if (cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        triggerHook("Network Validated");
                    }
                }
            };
            cm.registerNetworkCallback(
                    new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                    networkCallback);
        }
    }

    private synchronized void triggerHook(String reason) {
        int current = Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0);
        if (current == 1) return;

        long now = System.currentTimeMillis();
        if (now - lastActionTime < DEBOUNCE_MS) return;
        
        Log.w(TAG, "Enforcing adb_wifi_enabled = 1 (Reason: " + reason + ")");
        lastActionTime = now;
        try {
            Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
        } catch (Exception e) {
            Log.e(TAG, "Failed to write setting", e);
        }
    }

    private void ensureA11yEnabled() {
        try {
            String component = getPackageName() + "/" + AdbPopupAccessibilityService.class.getName();
            String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || !enabled.contains(component)) {
                Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, 
                    (enabled == null || enabled.isEmpty()) ? component : enabled + ":" + component);
            }
            Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Exception ignored) {}
    }

    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "ADB Hook", NotificationManager.IMPORTANCE_MIN));
            }
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setContentTitle("ADB Auto-Enable").setContentText("Reactive Watchdog Active").setSmallIcon(android.R.drawable.ic_menu_preferences).build();
    }

    @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(watchdogTask);
        if (adbObserver != null) getContentResolver().unregisterContentObserver(adbObserver);
        if (networkCallback != null) ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback);
    }
}
