package com.tpn.adbautoenable;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
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

    private volatile boolean isConfiguring = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "AdbConfigService onCreate()");
        createNotificationChannel();
        startForeground(1, createNotification("ADB Auto-Enable active"));
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
            }).start();
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

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

    private void configureAdbWithRetries() {
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            Log.i(TAG, "ADB enable attempt " + attempt + "/" + MAX_RETRY_ATTEMPTS);
            if (attempt == 1) updateNotification("Enabling ADB...");
            if (enableAdb()) {
                Log.i(TAG, "ADB enabled on attempt " + attempt);
                updateNotification("ADB Auto-Enable active");
                return;
            }
            if (attempt < MAX_RETRY_ATTEMPTS) {
                Log.i(TAG, "Attempt " + attempt + " failed, retrying in " + RETRY_DELAY_SECONDS + "s...");
                try {
                    Thread.sleep(RETRY_DELAY_SECONDS * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        Log.e(TAG, "All " + MAX_RETRY_ATTEMPTS + " attempts failed");
        updateNotification("ADB enable failed");
    }

    private boolean enableAdb() {
        try {
            Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
            Log.i(TAG, "adb_wifi_enabled = 1");
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied — grant WRITE_SECURE_SETTINGS via ADB", e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to enable ADB", e);
            return false;
        }
    }

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
