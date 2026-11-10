package au.bethesque.calendaralarms;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import au.bethesque.calendaralarms.utils.Log;

/**
 * TEMPORARY: a heavily logged version of the snapserver reachability check, for working
 * out why the check sometimes fails while the phone is on the home wifi (suspected cause:
 * Doze blocking the app's network access during the allow-while-idle start alarm).
 *
 * To remove once the cause is known:
 * - delete this class
 * - restore the plain check in CalendarAlarmScheduler#isSnapserverReachable:
 * <pre>
 *     try (Socket socket = new Socket()) {
 *         socket.connect(new InetSocketAddress(host, settings.getControlPort()), SNAPSERVER_REACHABLE_TIMEOUT_MS);
 *         return true;
 *     } catch (IOException e) {
 *         Log.i(TAG, "isSnapserverReachable: " + host + " unreachable", e);
 *         return false;
 *     }
 * </pre>
 * - remove the ACCESS_NETWORK_STATE permission from AndroidManifest.xml
 */
final class SnapserverReachabilityDiagnostics {
    private static final String TAG = "SnapserverReachability";

    private SnapserverReachabilityDiagnostics() {
    }

    /**
     * Returns whether a TCP connection to host:port can be made within timeoutMs, logging
     * the device's power and network state, and timing the DNS lookup and connect separately.
     */
    static boolean isReachable(Context context, String host, int port, int timeoutMs) {
        Log.i(TAG, "checking " + host + ":" + port + ", " + describeDeviceState(context));

        InetAddress address;
        long dnsStart = SystemClock.elapsedRealtime();
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            Log.i(TAG, "DNS lookup of " + host + " failed after "
                    + (SystemClock.elapsedRealtime() - dnsStart) + "ms", e);
            return false;
        }
        Log.i(TAG, "resolved " + host + " to " + address.getHostAddress() + " in "
                + (SystemClock.elapsedRealtime() - dnsStart) + "ms");

        long connectStart = SystemClock.elapsedRealtime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), timeoutMs);
            Log.i(TAG, "connected to " + address.getHostAddress() + ":" + port + " in "
                    + (SystemClock.elapsedRealtime() - connectStart) + "ms");
            return true;
        } catch (IOException e) {
            Log.i(TAG, "connect to " + address.getHostAddress() + ":" + port + " failed after "
                    + (SystemClock.elapsedRealtime() - connectStart) + "ms", e);
            return false;
        }
    }

    /**
     * Summarises the power and network state as this app sees it.
     */
    private static String describeDeviceState(Context context) {
        StringBuilder sb = new StringBuilder();
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            sb.append("interactive=").append(powerManager.isInteractive());
            sb.append(" powerSave=").append(powerManager.isPowerSaveMode());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                sb.append(" deviceIdle=").append(powerManager.isDeviceIdleMode());
                sb.append(" ignoringBatteryOptimizations=")
                        .append(powerManager.isIgnoringBatteryOptimizations(context.getPackageName()));
            }
        }

        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            try {
                // Deprecated, but still the only API that reports BLOCKED when the
                // app's network access is suspended (e.g. by Doze).
                NetworkInfo info = connectivityManager.getActiveNetworkInfo();
                sb.append(" activeNetworkInfo=")
                        .append(info == null ? "none" : info.getTypeName() + "/" + info.getDetailedState());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Network network = connectivityManager.getActiveNetwork();
                    sb.append(" activeNetwork=").append(network == null ? "none"
                            : describeCapabilities(connectivityManager.getNetworkCapabilities(network)));
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    sb.append(" dataSaver=").append(describeRestrictBackgroundStatus(
                            connectivityManager.getRestrictBackgroundStatus()));
                }
            } catch (SecurityException e) {
                sb.append(" network=unknown (").append(e).append(")");
            }
        }
        return sb.toString();
    }

    private static String describeCapabilities(NetworkCapabilities capabilities) {
        if (capabilities == null)
            return "no-capabilities";
        List<String> transports = new ArrayList<>();
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            transports.add("wifi");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
            transports.add("cellular");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            transports.add("ethernet");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            transports.add("vpn");
        return String.join("+", transports)
                + " internet=" + capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                + " validated=" + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
    }

    private static String describeRestrictBackgroundStatus(int status) {
        switch (status) {
            case ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED:
                return "off";
            case ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED:
                return "on-exempt";
            case ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED:
                return "on";
            default:
                return "unknown(" + status + ")";
        }
    }
}
