package eu.project_simple.adbautoenable;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

/**
 * Discovers active ADB network endpoints:
 * - Local Wi-Fi IP address
 * - TCP/IP port 5555
 * - Android 11+ TLS Wireless Debugging dynamic ports via mDNS (JmDNS & NsdManager)
 * - Custom port in service.adb.tcp.port
 */
public class AdbPortDiscovery {
    private static final String TAG = "ADBAutoEnable.Discovery";
    private static final long SCAN_TIMEOUT_MS = 3500;

    public static class AdbEndpoint {
        public final String ip;
        public final int port;
        public final String description;

        public AdbEndpoint(String ip, int port, String description) {
            this.ip = ip;
            this.port = port;
            this.description = description;
        }

        public String getConnectCommand() {
            return "adb connect " + ip + ":" + port;
        }

        public String getDisplayAddress() {
            return ip + ":" + port;
        }
    }

    public interface DiscoveryCallback {
        void onEndpointsDiscovered(String ipAddress, List<AdbEndpoint> endpoints, boolean isScanning);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private final AtomicBoolean isScanning = new AtomicBoolean(false);

    private WifiManager.MulticastLock multicastLock;
    private JmDNS jmdns;
    private ServiceListener jmdnsListener;
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener nsdListener;

    private final Map<Integer, AdbEndpoint> endpointsMap = Collections.synchronizedMap(new LinkedHashMap<>());
    private String currentIp;
    private DiscoveryCallback activeCallback;

    public AdbPortDiscovery(Context context) {
        this.context = context.getApplicationContext();
    }

    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            // Try preferred Wi-Fi or Ethernet interfaces first
            for (NetworkInterface intf : interfaces) {
                if (!intf.isUp() || intf.isLoopback()) continue;
                String name = intf.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("rndis")) {
                    for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
            // Fallback to any non-loopback IPv4
            for (NetworkInterface intf : interfaces) {
                if (!intf.isUp() || intf.isLoopback()) continue;
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error obtaining local IP address", e);
        }
        return null;
    }

    public static boolean isPortOpen(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            Log.i(TAG, "Port " + port + " on " + host + " is OPEN");
            return true;
        } catch (Exception e) {
            Log.d(TAG, "isPortOpen failed for " + host + ":" + port + " -> " + e.getMessage());
            return false;
        }
    }

    public void startDiscovery(DiscoveryCallback callback) {
        stopDiscovery();

        this.activeCallback = callback;
        this.endpointsMap.clear();
        this.isScanning.set(true);

        executor = Executors.newSingleThreadExecutor();
        executor.execute(this::runDiscovery);
    }

    private void runDiscovery() {
        currentIp = getLocalIpAddress();
        if (currentIp == null) {
            postResults(null, false);
            isScanning.set(false);
            return;
        }

        // Notify initial scanning state with IP
        postResults(currentIp, true);

        // 1. Check Standard TCP/IP Port 5555
        if (isPortOpen("127.0.0.1", 5555, 300) || isPortOpen(currentIp, 5555, 300)) {
            endpointsMap.put(5555, new AdbEndpoint(currentIp, 5555, "Standard TCP/IP"));
            postResults(currentIp, true);
        }

        // 2. Check system property service.adb.tcp.port
        checkSystemPropertyPort(currentIp);

        // 3. Start mDNS discovery via JmDNS & NsdManager
        startMdnsDiscovery(currentIp);

        // Schedule scan termination after SCAN_TIMEOUT_MS
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);
    }

    private final Runnable scanTimeoutRunnable = () -> {
        if (isScanning.get()) {
            cleanupMdns();
            isScanning.set(false);
            postResults(currentIp, false);
        }
    };

    private void checkSystemPropertyPort(String ip) {
        try {
            Process p = Runtime.getRuntime().exec("getprop service.adb.tcp.port");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    int customPort = Integer.parseInt(line.trim());
                    if (customPort > 0 && !endpointsMap.containsKey(customPort)) {
                        if (isPortOpen("127.0.0.1", customPort, 300) || isPortOpen(ip, customPort, 300)) {
                            endpointsMap.put(customPort, new AdbEndpoint(ip, customPort, "TCP/IP Custom Port"));
                            postResults(ip, true);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void startMdnsDiscovery(String ip) {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("ADBAutoEnableDiscovery");
                multicastLock.setReferenceCounted(true);
                multicastLock.acquire();
            }

            InetAddress inetAddr = InetAddress.getByName(ip);
            jmdns = JmDNS.create(inetAddr, "ADBAutoEnable");

            jmdnsListener = new ServiceListener() {
                @Override
                public void serviceAdded(ServiceEvent event) {
                    if (jmdns != null) {
                        try {
                            jmdns.requestServiceInfo(event.getType(), event.getName(), 1000);
                        } catch (Exception ignored) {}
                    }
                }

                @Override
                public void serviceRemoved(ServiceEvent event) {}

                @Override
                public void serviceResolved(ServiceEvent event) {
                    ServiceInfo info = event.getInfo();
                    if (info != null && info.getPort() > 0) {
                        handlePortDiscovered(info.getPort(), "Wireless Debugging (TLS)");
                    }
                }
            };

            jmdns.addServiceListener("_adb-tls-connect._tcp.local.", jmdnsListener);
        } catch (Throwable t) {
            Log.w(TAG, "JmDNS discovery initialization: " + t.getMessage());
        }

        // Parallel NsdManager discovery as complementary detector
        try {
            nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsdManager != null) {
                nsdListener = new NsdManager.DiscoveryListener() {
                    @Override public void onStartDiscoveryFailed(String s, int i) {}
                    @Override public void onStopDiscoveryFailed(String s, int i) {}
                    @Override public void onDiscoveryStarted(String s) {}
                    @Override public void onDiscoveryStopped(String s) {}
                    @Override public void onServiceLost(NsdServiceInfo nsdServiceInfo) {}

                    @Override
                    public void onServiceFound(NsdServiceInfo serviceInfo) {
                        try {
                            nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                                @Override public void onResolveFailed(NsdServiceInfo s, int i) {}
                                @Override
                                public void onServiceResolved(NsdServiceInfo resolved) {
                                    if (resolved != null && resolved.getPort() > 0) {
                                        handlePortDiscovered(resolved.getPort(), "Wireless Debugging (TLS)");
                                    }
                                }
                            });
                        } catch (Exception ignored) {}
                    }
                };
                nsdManager.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, nsdListener);
            }
        } catch (Throwable t) {
            Log.w(TAG, "NsdManager discovery: " + t.getMessage());
        }
    }

    private void handlePortDiscovered(int port, String desc) {
        if (port <= 0 || port > 65535) return;
        if (endpointsMap.containsKey(port)) return;

        // Verify that the discovered port is actually open and listening
        boolean isOpen = isPortOpen("127.0.0.1", port, 250) ||
                (currentIp != null && isPortOpen(currentIp, port, 250));
        if (!isOpen) {
            Log.d(TAG, "Discarding stale/closed port from mDNS: " + port);
            return;
        }

        Log.i(TAG, "Validated active open port: " + port + " (" + desc + ")");
        endpointsMap.put(port, new AdbEndpoint(currentIp, port, desc));
        postResults(currentIp, true);
    }

    private void postResults(String ip, boolean scanning) {
        List<AdbEndpoint> list = new ArrayList<>(endpointsMap.values());
        mainHandler.post(() -> {
            if (activeCallback != null) {
                activeCallback.onEndpointsDiscovered(ip, list, scanning);
            }
        });
    }

    private void cleanupMdns() {
        try {
            if (jmdns != null && jmdnsListener != null) {
                jmdns.removeServiceListener("_adb-tls-connect._tcp.local.", jmdnsListener);
            }
        } catch (Exception ignored) {}

        try {
            if (jmdns != null) {
                jmdns.close();
                jmdns = null;
            }
        } catch (Exception ignored) {}

        try {
            if (nsdManager != null && nsdListener != null) {
                nsdManager.stopServiceDiscovery(nsdListener);
                nsdListener = null;
            }
        } catch (Exception ignored) {}

        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
                multicastLock = null;
            }
        } catch (Exception ignored) {}
    }

    public void stopDiscovery() {
        mainHandler.removeCallbacks(scanTimeoutRunnable);
        isScanning.set(false);
        cleanupMdns();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
