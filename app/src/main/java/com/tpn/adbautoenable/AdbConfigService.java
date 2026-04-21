package com.tpn.adbautoenable;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AdbConfigService extends Service {
    private static final String TAG = "ADBAutoEnable";
    private static final String CHANNEL_ID = "ADBAutoEnableChannel";
    private static final int INITIAL_BOOT_DELAY_SECONDS = 10;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int RETRY_DELAY_SECONDS = 10;
    private static final int MONITOR_INTERVAL_SECONDS = 30;

    private volatile boolean isConfiguring = false;
    private volatile boolean isMonitoring = false;
    private volatile long lastEnableTimestampMs = 0;
    private static final long ENABLE_DEBOUNCE_MS = 10_000;

    private ConnectivityManager.NetworkCallback networkCallback;
    private ContentObserver adbSettingObserver;
    private HandlerThread observerThread;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "AdbConfigService onCreate()");
        createNotificationChannel();
        Notification n = createNotification("ADB Auto-Enable active");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
        ensureAccessibilityServiceEnabled();
        registerAdbSettingObserver();
        registerNetworkCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "AdbConfigService onStartCommand()");

        boolean bootConfig = intent != null && intent.getBooleanExtra("boot_config", false);

        if (bootConfig && !isConfiguring) {
            isConfiguring = true;
            new Thread(() -> {
                try {
                    waitForWifiConnection();
                    waitForBootStabilization();
                    configureAdbWithRetries();
                } catch (Exception e) {
                    Log.e(TAG, "Error in boot config thread", e);
                } finally {
                    isConfiguring = false;
                }
                startMonitoringLoop();
            }).start();
        } else if (!bootConfig && !isConfiguring) {
            // Service restarted or manual start — enable immediately then monitor
            new Thread(() -> {
                configureAdbWithRetries();
                startMonitoringLoop();
            }).start();
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isMonitoring = false;

        if (adbSettingObserver != null) {
            getContentResolver().unregisterContentObserver(adbSettingObserver);
        }
        if (observerThread != null) {
            observerThread.quit();
        }
        if (networkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(networkCallback);
        }
        Log.i(TAG, "AdbConfigService onDestroy()");
    }

    // --- Reactive: fires instantly when adb_wifi_enabled changes ---

    private void registerAdbSettingObserver() {
        observerThread = new HandlerThread("AdbObserver");
        observerThread.start();
        Handler handler = new Handler(observerThread.getLooper());

        Uri settingUri = Settings.Global.getUriFor("adb_wifi_enabled");
        adbSettingObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                int current = Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0);
                if (current == 1) return;
                // When we write 1, the system briefly resets to 0 while the trust popup
                // is pending. Skip retries during that window — the accessibility
                // service will click the popup and the setting will flip to 1.
                if (System.currentTimeMillis() - lastEnableTimestampMs < ENABLE_DEBOUNCE_MS) {
                    Log.i(TAG, "ContentObserver: recent enable in flight, skipping retry");
                    return;
                }
                Log.w(TAG, "ContentObserver: adb_wifi_enabled changed to 0 — re-enabling");
                configureAdbWithRetries();
            }
        };

        getContentResolver().registerContentObserver(settingUri, false, adbSettingObserver);
        Log.i(TAG, "ContentObserver registered for adb_wifi_enabled");
    }

    // --- Reactive: fires when a new WiFi network becomes available ---

    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "Network available — ensuring ADB is enabled");
                configureAdbWithRetries();
            }
        };

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        cm.registerNetworkCallback(request, networkCallback);
        Log.i(TAG, "Network callback registered");
    }

    // --- Backup: polling loop, belt-and-suspenders ---

    private void startMonitoringLoop() {
        if (isMonitoring) return;
        isMonitoring = true;
        Log.i(TAG, "Starting ADB poll loop (every " + MONITOR_INTERVAL_SECONDS + "s)");
        new Thread(() -> {
            while (isMonitoring) {
                try {
                    Thread.sleep(MONITOR_INTERVAL_SECONDS * 1000L);
                    int current = Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0);
                    if (current == 1) continue;
                    if (System.currentTimeMillis() - lastEnableTimestampMs < ENABLE_DEBOUNCE_MS) {
                        Log.i(TAG, "Poll: recent enable in flight, skipping retry");
                        continue;
                    }
                    Log.w(TAG, "Poll: ADB disabled — re-enabling");
                    configureAdbWithRetries();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Poll loop error", e);
                }
            }
            Log.i(TAG, "Poll loop stopped");
        }).start();
    }

    // --- Boot sequence ---

    private void waitForWifiConnection() throws InterruptedException {
        Log.i(TAG, "Waiting for WiFi connection...");
        updateNotification("Waiting for WiFi...");
        int maxWaitSeconds = 60;
        int waited = 0;

        while (waited < maxWaitSeconds) {
            String ip = getDeviceIP();
            if (!ip.equals("127.0.0.1") && !ip.equals("0.0.0.0")) {
                Log.i(TAG, "WiFi connected, IP: " + ip);
                return;
            }
            Thread.sleep(1000);
            waited++;
            if (waited % 10 == 0) {
                Log.i(TAG, "Still waiting for WiFi... (" + waited + "s)");
                updateNotification("Waiting for WiFi... (" + waited + "s)");
            }
        }
        Log.w(TAG, "WiFi wait timeout — proceeding anyway");
    }

    private void waitForBootStabilization() throws InterruptedException {
        Log.i(TAG, "Waiting " + INITIAL_BOOT_DELAY_SECONDS + "s for system to stabilize...");
        for (int i = INITIAL_BOOT_DELAY_SECONDS; i > 0; i--) {
            updateNotification("Stabilizing... " + i + "s");
            Thread.sleep(1000);
        }
        Log.i(TAG, "Boot stabilization complete");
    }

    // --- Core enable logic ---

    private volatile boolean isOperationInProgress = false;

    private synchronized void configureAdbWithRetries() {
        if (isOperationInProgress) {
            Log.d(TAG, "Configuration already in progress, skipping");
            return;
        }
        isOperationInProgress = true;
        
        new Thread(() -> {
            try {
                for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
                    Log.i(TAG, "ADB enable attempt " + attempt + "/" + MAX_RETRY_ATTEMPTS);
                    if (attempt == 1) updateNotification("Enabling ADB...");
                    
                    lastEnableTimestampMs = System.currentTimeMillis();
                    Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
                    
                    // Wait a bit to see if it sticks or if a popup appears
                    Thread.sleep(2000);
                    
                    int current = Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0);
                    if (current == 1) {
                        Log.i(TAG, "ADB confirmed enabled");
                        updateNotification("ADB Auto-Enable active");
                        return;
                    }

                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        Log.i(TAG, "Attempt " + attempt + " failed (possibly pending popup), waiting...");
                        Thread.sleep(RETRY_DELAY_SECONDS * 1000L);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in configuration thread", e);
            } finally {
                isOperationInProgress = false;
            }
        }).start();
    }

    // --- Accessibility service self-enable ---
    //
    // The wireless-debugging trust popup (com.oculus.os.vrusb/.WifiDebuggingAlertActivity)
    // is shown once per reboot on Meta Quest because Meta's AdbService clears its
    // in-memory trusted-BSSID list on boot. We can't call IAdbManager.allowWirelessDebugging()
    // directly (needs MANAGE_DEBUGGING, signature|privileged, not grantable via pm grant).
    // Instead we register our own AccessibilityService that auto-clicks "Always allow"
    // when the popup appears. WRITE_SECURE_SETTINGS lets us self-enable it.

    private static final String A11Y_SERVICE_COMPONENT =
            "com.tpn.adbautoenable/com.tpn.adbautoenable.AdbPopupAccessibilityService";

    private void ensureAccessibilityServiceEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) enabled = "";
            if (!enabled.contains(A11Y_SERVICE_COMPONENT)) {
                String updated = enabled.isEmpty()
                        ? A11Y_SERVICE_COMPONENT
                        : enabled + ":" + A11Y_SERVICE_COMPONENT;
                Settings.Secure.putString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
                Log.i(TAG, "Enabled accessibility service: " + A11Y_SERVICE_COMPONENT);
            }
            Settings.Secure.putInt(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (SecurityException e) {
            Log.e(TAG, "Missing WRITE_SECURE_SETTINGS — cannot enable a11y service", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to enable accessibility service", e);
        }
    }

    // --- Helpers ---

    private String getDeviceIP() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return "127.0.0.1";
            int ip = wm.getConnectionInfo().getIpAddress();
            byte[] bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ip).array();
            String addr = InetAddress.getByAddress(bytes).getHostAddress();
            return addr != null ? addr : "127.0.0.1";
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "ADB Auto-Enable", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification createNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("ADB Auto-Enable")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_preferences)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(1, createNotification(text));
        } catch (Exception e) {
            Log.e(TAG, "Error updating notification", e);
        }
    }
}
