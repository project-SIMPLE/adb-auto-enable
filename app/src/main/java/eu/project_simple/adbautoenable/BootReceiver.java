package eu.project_simple.adbautoenable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "ADBAutoEnable";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Received broadcast: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            Log.i(TAG, "Boot event detected, starting ADB configuration service...");
            context.startForegroundService(new Intent(context, AdbConfigService.class));
        } else {
            Log.i(TAG, "Ignoring broadcast: " + action);
        }
    }
}
