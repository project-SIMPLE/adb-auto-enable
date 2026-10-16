package eu.project_simple.adbautoenable;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.content.pm.ServiceInfo;
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
    private static final int NOTIFICATION_ID = 1;
    private static final long DEBOUNCE_MS = 500; 
    private static final long WATCHDOG_INTERVAL_MS = 60_000; // 1 minute fallback

    private static volatile boolean isServiceRunning = false;

    public interface ServiceStateListener {
        void onServiceStateChanged(boolean isRunning);
    }

    private static volatile ServiceStateListener stateListener;

    public static void setServiceStateListener(ServiceStateListener listener) {
        stateListener = listener;
    }

    public static boolean isRunning() {
        return isServiceRunning;
    }

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
        isServiceRunning = true;
        if (stateListener != null) {
            stateListener.onServiceStateChanged(true);
        }
        Log.i(TAG, "Service Starting");
        promoteToForeground();
        
        ensureA11yEnabled(this);
        registerHooks();
        
        // Start periodic fallback
        handler.postDelayed(watchdogTask, WATCHDOG_INTERVAL_MS);
        
        // Immediate check
        triggerHook("Service Start");
    }

    private void promoteToForeground() {
        try {
            Notification notification = createNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } catch (Throwable t) {
                    Log.w(TAG, "startForeground with specialUse failed, falling back: " + t.getMessage());
                    startForeground(NOTIFICATION_ID, notification);
                }
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to startForeground: " + e.getMessage(), e);
        }
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

    public static void ensureA11yEnabled(Context context) {
        try {
            String component = context.getPackageName() + "/" + AdbPopupAccessibilityService.class.getName();
            String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || !enabled.contains(component)) {
                Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, 
                    (enabled == null || enabled.isEmpty()) ? component : enabled + ":" + component);
            }
            Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Exception ignored) {}
    }

    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "ADB Auto-Enable Service",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Monitors and automatically keeps Wireless ADB enabled");
                nm.createNotificationChannel(channel);
            }
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                        (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE) :
                        PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                new Notification.Builder(this, CHANNEL_ID) :
                new Notification.Builder(this);
        return b.setContentTitle("ADB Auto-Enable")
                .setContentText("Watchdog Active")
                .setSmallIcon(android.R.drawable.ic_menu_preferences)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promoteToForeground();
        triggerHook("onStartCommand");
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        isServiceRunning = false;
        if (stateListener != null) {
            stateListener.onServiceStateChanged(false);
        }
        super.onDestroy();
        handler.removeCallbacks(watchdogTask);
        if (adbObserver != null) getContentResolver().unregisterContentObserver(adbObserver);
        if (networkCallback != null) ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback);
    }
}
