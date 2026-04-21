package com.tpn.adbautoenable;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.widget.TextView;
import android.util.Log;

public class MainActivity extends Activity {
    private static final String TAG = "ADBAutoEnable.Main";
    private Handler handler = new Handler();
    private Runnable finishRunnable = this::finish;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "MainActivity onCreate");
        
        TextView tv = new TextView(this);
        tv.setText("ADB Auto-Enable is configuring wireless debugging...");
        tv.setPadding(50, 50, 50, 50);
        setContentView(tv);

        // Start the background service
        Intent svc = new Intent(this, AdbConfigService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }

        // Check if we need to enable ADB
        int current = Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0);
        if (current == 0) {
            Log.i(TAG, "ADB is disabled. Enabling from Activity to ensure correct display...");
            try {
                Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
            } catch (Exception e) {
                Log.e(TAG, "Failed to write adb_wifi_enabled", e);
            }
            // Wait longer for the popup to appear and be clicked by the accessibility service
            handler.postDelayed(finishRunnable, 5000);
        } else {
            Log.i(TAG, "ADB is already enabled.");
            handler.postDelayed(finishRunnable, 1500);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(finishRunnable);
    }
}
