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
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

/**
 * Service that monitors and enforces 'adb_wifi_enabled = 1'.
 * It handles WiFi availability, boot stabilization, and system setting changes.
 */
public class AdbConfigService extends Service {
    private static final String TAG = "ADBAutoEnable";
    private static final String CHANNEL_ID = "ADBAutoEnableChannel";
    private static final int INITIAL_BOOT_DELAY_SECONDS = 15;
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final int RETRY_DELAY_SECONDS = 10;
    private static final int MONITOR_INTERVAL_SECONDS = 60;
    private static final long ENABLE_DEBOUNCE_MS = 15_000;

    private volatile boolean isOperationInProgress = false;
    private volatile boolean isMonitoring = false;
    private long lastEnableTimestampMs = 0;

    private ConnectivityManager.NetworkCallback networkCallback;
    private ContentObserver adbSettingObserver;
    private HandlerThread observerThread;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service Starting");
        createNotificationChannel();
        Notification n = createNotification("Monitoring Wireless ADB");
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
        boolean bootConfig = intent != null && intent.getBooleanExtra("boot_config", false);

        new Thread(() -> {
            try {
                if (bootConfig) {
                    Thread.sleep(INITIAL_BOOT_DELAY_SECONDS * 1000L);
                }
                configureAdbWithRetries();
                startMonitoringLoop();
            } catch (Exception e) {
                Log.e(TAG, "Startup error", e);
            }
        }).start();

        return START_STICKY;
    }

    private synchronized void configureAdbWithRetries() {
        if (isOperationInProgress) return;
        isOperationInProgress = true;
        
        new Thread(() -> {
            try {
                for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
                    int current = Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0);
                    if (current == 1) {
                        Log.i(TAG, "ADB is enabled");
                        return;
                    }

                    Log.i(TAG, "Enabling ADB (attempt " + attempt + ")");
                    lastEnableTimestampMs = System.currentTimeMillis();
                    Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
                    
                    Thread.sleep(RETRY_DELAY_SECONDS * 1000L);
                }
            } catch (Exception e) {
                Log.e(TAG, "Config error", e);
            } finally {
                isOperationInProgress = false;
            }
        }).start();
    }

    private void startMonitoringLoop() {
        if (isMonitoring) return;
        isMonitoring = true;
        new Thread(() -> {
            while (isMonitoring) {
                try {
                    Thread.sleep(MONITOR_INTERVAL_SECONDS * 1000L);
                    if (Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 0) {
                        if (System.currentTimeMillis() - lastEnableTimestampMs > ENABLE_DEBOUNCE_MS) {
                            configureAdbWithRetries();
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();
    }

    private void registerAdbSettingObserver() {
        observerThread = new HandlerThread("AdbObserver");
        observerThread.start();
        adbSettingObserver = new ContentObserver(new Handler(observerThread.getLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                if (Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 0) {
                    if (System.currentTimeMillis() - lastEnableTimestampMs > ENABLE_DEBOUNCE_MS) {
                        configureAdbWithRetries();
                    }
                }
            }
        };
        getContentResolver().registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, adbSettingObserver);
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                configureAdbWithRetries();
            }
        };
        cm.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback);
    }

    private void ensureAccessibilityServiceEnabled() {
        try {
            String component = getPackageName() + "/" + AdbPopupAccessibilityService.class.getName();
            String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || !enabled.contains(component)) {
                Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, 
                    (enabled == null || enabled.isEmpty()) ? component : enabled + ":" + component);
            }
            Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Exception e) {
            Log.e(TAG, "A11y auto-enable failed", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "ADB Auto-Enable", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private Notification createNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setContentTitle("ADB Auto-Enable").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_preferences).build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isMonitoring = false;
        if (adbSettingObserver != null) getContentResolver().unregisterContentObserver(adbSettingObserver);
        if (observerThread != null) observerThread.quit();
        if (networkCallback != null) ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback);
    }
}
