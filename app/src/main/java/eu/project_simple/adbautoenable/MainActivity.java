package eu.project_simple.adbautoenable;

import android.Manifest;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.util.Log;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "ADBAutoEnablePrefs";
    private static final String KEY_WATCHDOG_ENABLED = "watchdog_enabled";

    // Views
    private View cardPermissionWarning;
    private View cardA11yWarning;
    private TextView textPermCommand;
    private TextView textA11yCommand;
    private MaterialButton btnRerunA11y;
    private MaterialButton btnOpenA11ySettings;

    private MaterialSwitch switchAdbWifi;
    private TextView badgeAdbStatus;
    private MaterialSwitch switchWatchdog;
    private TextView badgeWatchdogStatus;

    private TextView textDeviceIp;
    private ProgressBar progressScanning;
    private LinearLayout layoutEndpointsContainer;
    private TextView textNoEndpoints;

    private MaterialButton btnRefresh;
    private MaterialButton btnOpenTermux;

    // Helpers
    private AdbPortDiscovery portDiscovery;
    private ContentObserver adbSettingsObserver;
    private SharedPreferences prefs;
    private String primaryConnectCommand = null;
    private boolean isUpdatingSwitches = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        portDiscovery = new AdbPortDiscovery(this);

        initViews();
        setupListeners();

        AdbConfigService.setServiceStateListener(running -> runOnUiThread(this::updateUiStates));

        // Start the watchdog service on first launch unless explicitly disabled
        boolean watchdogPref = prefs.getBoolean(KEY_WATCHDOG_ENABLED, true);
        if (watchdogPref && !isWatchdogRunning()) {
            startWatchdogService();
        }

        // Request notification permission on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }

        setupSettingsObserver();
    }

    private void initViews() {
        cardPermissionWarning = findViewById(R.id.cardPermissionWarning);
        cardA11yWarning = findViewById(R.id.cardA11yWarning);
        textPermCommand = findViewById(R.id.textPermCommand);
        textA11yCommand = findViewById(R.id.textA11yCommand);
        btnRerunA11y = findViewById(R.id.btnRerunA11y);
        btnOpenA11ySettings = findViewById(R.id.btnOpenA11ySettings);

        switchAdbWifi = findViewById(R.id.switchAdbWifi);
        badgeAdbStatus = findViewById(R.id.badgeAdbStatus);
        switchWatchdog = findViewById(R.id.switchWatchdog);
        badgeWatchdogStatus = findViewById(R.id.badgeWatchdogStatus);

        textDeviceIp = findViewById(R.id.textDeviceIp);
        progressScanning = findViewById(R.id.progressScanning);
        layoutEndpointsContainer = findViewById(R.id.layoutEndpointsContainer);
        textNoEndpoints = findViewById(R.id.textNoEndpoints);

        btnRefresh = findViewById(R.id.btnRefresh);
        btnOpenTermux = findViewById(R.id.btnOpenTermux);
    }

    private void setupListeners() {
        // ADB Wireless Switch
        switchAdbWifi.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isUpdatingSwitches) return;

            if (!hasWriteSecureSettings()) {
                isUpdatingSwitches = true;
                buttonView.setChecked(!isChecked);
                isUpdatingSwitches = false;
                showPermissionDialog();
                return;
            }

            try {
                if (isChecked) {
                    Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
                    // Also start watchdog service if enabled in prefs
                    if (prefs.getBoolean(KEY_WATCHDOG_ENABLED, true) && !AdbConfigService.isRunning()) {
                        startWatchdogService();
                    }
                    showToast(getString(R.string.adb_wireless_title) + " " + getString(R.string.status_enabled));
                } else {
                    // Stop watchdog first so it doesn't fight the user's manual action
                    if (AdbConfigService.isRunning()) {
                        stopWatchdogService();
                    }
                    Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 0);
                    showToast(getString(R.string.adb_wireless_title) + " " + getString(R.string.status_disabled));
                }
            } catch (Exception e) {
                showToast("Error updating ADB setting: " + e.getMessage());
            }

            updateUiStates();
            refreshEndpoints();
        });

        // Watchdog Service Switch
        switchWatchdog.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isUpdatingSwitches) return;

            prefs.edit().putBoolean(KEY_WATCHDOG_ENABLED, isChecked).apply();
            if (isChecked) {
                startWatchdogService();
                showToast(getString(R.string.watchdog_title) + " " + getString(R.string.status_running));
            } else {
                stopWatchdogService();
                showToast(getString(R.string.watchdog_title) + " " + getString(R.string.status_stopped));
            }
            updateUiStates();
        });

        // Refresh Button
        btnRefresh.setOnClickListener(v -> {
            showToast("Refreshing status…");
            refreshAll();
        });

        // Tap Permission Command to Copy
        textPermCommand.setOnClickListener(v -> {
            copyToClipboard("ADB Command", getString(R.string.perm_grant_cmd));
        });

        // Tap Accessibility Command to Copy
        textA11yCommand.setOnClickListener(v -> {
            copyToClipboard("ADB Command", getString(R.string.a11y_enable_cmd));
        });

        // Rerun Auto Configuration Button
        btnRerunA11y.setOnClickListener(v -> {
            if (hasWriteSecureSettings()) {
                AdbConfigService.ensureA11yEnabled(this);
                updateUiStates();
                if (isAccessibilityActive()) {
                    showToast(getString(R.string.a11y_autoconfig_success));
                } else {
                    showToast(getString(R.string.a11y_autoconfig_failed));
                }
            } else {
                showToast(getString(R.string.a11y_autoconfig_failed));
                updateUiStates();
            }
        });

        // Open Accessibility Settings Button (Targeting standard AOSP settings on MetaOS)
        btnOpenA11ySettings.setOnClickListener(v -> openAccessibilitySettings());

        // Open in Termux Button
        btnOpenTermux.setOnClickListener(v -> {
            if (primaryConnectCommand != null) {
                copyToClipboard("ADB Connect", primaryConnectCommand);
            }
            try {
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.termux");
                if (launchIntent != null) {
                    startActivity(launchIntent);
                }
            } catch (Exception e) {
                showToast("Could not launch Termux");
            }
        });
    }

    private void setupSettingsObserver() {
        adbSettingsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                runOnUiThread(() -> {
                    updateUiStates();
                    refreshEndpoints();
                });
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor("adb_wifi_enabled"), false, adbSettingsObserver);
        } catch (Exception ignored) {}

        refreshAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            getContentResolver().unregisterContentObserver(adbSettingsObserver);
        } catch (Exception ignored) {}
        portDiscovery.stopDiscovery();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AdbConfigService.setServiceStateListener(null);
    }

    private void refreshAll() {
        updateUiStates();
        refreshEndpoints();
    }

    private void updateUiStates() {
        boolean hasPermission = hasWriteSecureSettings();
        cardPermissionWarning.setVisibility(hasPermission ? View.GONE : View.VISIBLE);

        if (hasPermission) {
            AdbConfigService.ensureA11yEnabled(this);
        }

        boolean a11yActive = isAccessibilityActive();
        cardA11yWarning.setVisibility(a11yActive ? View.GONE : View.VISIBLE);

        int adbSetting = Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0);
        boolean isAdbEnabled = (adbSetting == 1);

        boolean isWatchdogRunning = isWatchdogRunning();

        isUpdatingSwitches = true;
        switchAdbWifi.setChecked(isAdbEnabled);
        switchWatchdog.setChecked(isWatchdogRunning);
        isUpdatingSwitches = false;

        // ADB Status Badge
        if (isAdbEnabled) {
            badgeAdbStatus.setText(R.string.status_enabled);
            badgeAdbStatus.setBackgroundResource(R.drawable.status_badge_green);
            badgeAdbStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green));
        } else {
            badgeAdbStatus.setText(R.string.status_disabled);
            badgeAdbStatus.setBackgroundResource(R.drawable.status_badge_gray);
            badgeAdbStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }

        // Watchdog Status Badge
        if (isWatchdogRunning) {
            badgeWatchdogStatus.setText(R.string.status_running);
            badgeWatchdogStatus.setBackgroundResource(R.drawable.status_badge_green);
            badgeWatchdogStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green));
        } else {
            badgeWatchdogStatus.setText(R.string.status_stopped);
            badgeWatchdogStatus.setBackgroundResource(R.drawable.status_badge_gray);
            badgeWatchdogStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }

        // Termux visibility
        boolean termuxInstalled = isTermuxInstalled();
        btnOpenTermux.setVisibility(termuxInstalled ? View.VISIBLE : View.GONE);
    }

    private void refreshEndpoints() {
        int adbSetting = Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0);
        boolean isAdbEnabled = (adbSetting == 1);

        if (!isAdbEnabled) {
            String ip = AdbPortDiscovery.getLocalIpAddress();
            textDeviceIp.setText(ip != null ? ip : getString(R.string.ip_not_connected));
            progressScanning.setVisibility(View.GONE);
            layoutEndpointsContainer.removeAllViews();
            textNoEndpoints.setVisibility(View.VISIBLE);
            textNoEndpoints.setText(R.string.ports_adb_disabled);
            primaryConnectCommand = null;
            return;
        }

        progressScanning.setVisibility(View.VISIBLE);
        portDiscovery.startDiscovery((ipAddress, endpoints, isScanning) -> {
            progressScanning.setVisibility(isScanning ? View.VISIBLE : View.GONE);
            textDeviceIp.setText(ipAddress != null ? ipAddress : getString(R.string.ip_not_connected));

            layoutEndpointsContainer.removeAllViews();

            if (endpoints != null && !endpoints.isEmpty()) {
                textNoEndpoints.setVisibility(View.GONE);
                primaryConnectCommand = endpoints.get(0).getConnectCommand();

                LayoutInflater inflater = LayoutInflater.from(MainActivity.this);
                for (AdbPortDiscovery.AdbEndpoint endpoint : endpoints) {
                    View item = inflater.inflate(R.layout.item_adb_endpoint, layoutEndpointsContainer, false);
                    TextView textAddress = item.findViewById(R.id.textEndpointAddress);
                    TextView textType = item.findViewById(R.id.textEndpointType);

                    textAddress.setText(endpoint.getDisplayAddress());
                    textType.setText(endpoint.description);

                    item.setOnClickListener(v -> copyToClipboard("ADB Endpoint", endpoint.getConnectCommand()));

                    layoutEndpointsContainer.addView(item);
                }
            } else {
                primaryConnectCommand = null;
                textNoEndpoints.setVisibility(View.VISIBLE);
                textNoEndpoints.setText(isScanning ? R.string.scanning_ports : R.string.no_ports_found);
            }
        });
    }

    private boolean hasWriteSecureSettings() {
        return checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isAccessibilityActive() {
        if (AdbPopupAccessibilityService.isConnected()) {
            return true;
        }
        try {
            int a11yMaster = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (a11yMaster != 1) return false;

            String component = getPackageName() + "/" + AdbPopupAccessibilityService.class.getName();
            String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.contains(component);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isTermuxInstalled() {
        try {
            getPackageManager().getPackageInfo("com.termux", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void startWatchdogService() {
        Intent svc = new Intent(this, AdbConfigService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Error starting watchdog service: " + e.getMessage(), e);
        }
    }

    private void stopWatchdogService() {
        try {
            stopService(new Intent(this, AdbConfigService.class));
        } catch (Exception e) {
            Log.e("MainActivity", "Error stopping watchdog service: " + e.getMessage(), e);
        }
    }

    private boolean isWatchdogRunning() {
        if (AdbConfigService.isRunning()) return true;
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(50);
            if (services != null) {
                for (ActivityManager.RunningServiceInfo s : services) {
                    if (AdbConfigService.class.getName().equals(s.service.getClassName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.setPackage("com.android.settings");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            // Fallback without package specification if com.android.settings fails
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ex) {
                showToast("Could not open Accessibility settings: " + ex.getMessage());
            }
        }
    }

    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.perm_error_title)
                .setMessage(getString(R.string.perm_error_desc) + "\n\n" + getString(R.string.perm_grant_cmd))
                .setPositiveButton(R.string.copy_command, (dialog, which) -> {
                    copyToClipboard("ADB Command", getString(R.string.perm_grant_cmd));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText(label, text);
            clipboard.setPrimaryClip(clip);
            showToast(getString(R.string.copied_to_clipboard) + ": " + text);
        }
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
