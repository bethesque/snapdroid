package de.badaix.snapcast.calendar;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import de.badaix.snapcast.CalendarAlarmReceiver;
import de.badaix.snapcast.utils.Settings;

/**
 * Fetches and persists the "Calendar Alarms URL" feed, and drives the AlarmManager
 * schedule that starts the Snapclient 15 seconds before each upcoming play_datetime,
 * lets it run for 5 minutes, then stops it and arms the next one.
 */
public class CalendarAlarmScheduler {
    private static final String TAG = "CalendarAlarmScheduler";

    private static final int REQUEST_CODE_ALARM_START = 2001;
    private static final int REQUEST_CODE_ALARM_STOP = 2002;
    private static final long LEAD_TIME_MS = 15_000L;
    private static final long PLAY_DURATION_MS = 5 * 60_000L;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final String NOTIFICATIONS_PATH = "/alarm/notifications";
    private static final int SNAPSERVER_REACHABLE_TIMEOUT_MS = 3_000;

    private CalendarAlarmScheduler() {
    }

    public static void fetchAndStore(Context context, Runnable onSuccess, Consumer<Exception> onError) {
        Context appContext = context.getApplicationContext();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                String baseUrl = Settings.getInstance(appContext).getCalendarAlarmsUrl();
                String body = fetch(baseUrl.replaceAll("/+$", "") + NOTIFICATIONS_PATH);
                // Sanity-parse before persisting so garbage never overwrites a working schedule.
                new JSONObject(body);
                Settings.getInstance(appContext).setCalendarNotificationsJson(body);
                mainHandler.post(onSuccess);
            } catch (Exception e) {
                Log.e(TAG, "fetchAndStore failed", e);
                mainHandler.post(() -> onError.accept(e));
            }
        }).start();
    }

    private static String fetch(String urlString) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Unexpected HTTP response code: " + responseCode);
            }

            StringBuilder sb = new StringBuilder();
            try (InputStream is = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } finally {
            connection.disconnect();
        }
    }

    public static List<CalendarNotification> loadStored(Context context) {
        List<CalendarNotification> notifications = new ArrayList<>();
        String json = Settings.getInstance(context).getCalendarNotificationsJson();
        if (json.trim().isEmpty())
            return notifications;

        try {
            JSONArray array = new JSONObject(json).getJSONArray("notifications");
            for (int i = 0; i < array.length(); i++) {
                try {
                    notifications.add(new CalendarNotification(array.getJSONObject(i)));
                } catch (Exception e) {
                    Log.w(TAG, "Skipping malformed notification entry at index " + i, e);
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Stored calendar notifications JSON is malformed", e);
        }
        return notifications;
    }

    /**
     * Checks whether the snapserver's control port can be reached right now, used as a
     * proxy for "connected to the home network" without needing SSID/location permissions.
     */
    public static boolean isSnapserverReachable(Context context) {
        Settings settings = Settings.getInstance(context);
        String host = settings.getHost();
        if (host.isEmpty())
            return false;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, settings.getControlPort()), SNAPSERVER_REACHABLE_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            Log.d(TAG, "isSnapserverReachable: " + host + " unreachable", e);
            return false;
        }
    }

    /**
     * Returns the upcoming notification with the earliest play_datetime, or null if
     * the stored feed has no entries left in the future.
     */
    public static CalendarNotification getNext(Context context) {
        Instant now = Instant.now();
        CalendarNotification next = null;
        for (CalendarNotification notification : loadStored(context)) {
            Instant playInstant = notification.getPlayDateTime().toInstant();
            if (playInstant.isAfter(now) && (next == null || playInstant.isBefore(next.getPlayDateTime().toInstant()))) {
                next = notification;
            }
        }
        return next;
    }

    /**
     * Cancels any pending start alarm and re-arms it for the earliest upcoming
     * play_datetime in the stored feed. Returns true if an upcoming notification
     * was found (regardless of whether the OS alarm could actually be scheduled).
     */
    public static boolean scheduleNext(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent startPendingIntent = buildStartPendingIntent(context);
        alarmManager.cancel(startPendingIntent);

        CalendarNotification next = getNext(context);
        if (next == null) {
            Log.d(TAG, "scheduleNext: no upcoming notifications, nothing scheduled");
            return false;
        }

        long triggerAtMillis = Math.max(System.currentTimeMillis(), next.getPlayDateTime().toInstant().toEpochMilli() - LEAD_TIME_MS);
        Log.d(TAG, "scheduleNext: \"" + next.getSummary() + "\" at " + next.getPlayDateTime());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "scheduleNext: exact alarm permission not granted, cannot schedule");
            return true;
        }

        try {
            setExactAlarm(alarmManager, AlarmManager.RTC_WAKEUP, triggerAtMillis, startPendingIntent);
        } catch (SecurityException e) {
            Log.w(TAG, "scheduleNext: exact alarm permission revoked", e);
        }
        return true;
    }

    /**
     * Cancels the pending stop alarm for the currently playing occurrence (if any) without
     * touching the next scheduled start, since scheduleNext already excludes occurrences
     * whose play_datetime has passed.
     */
    public static void cancelStop(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarmManager.cancel(buildStopPendingIntent(context));
    }

    public static void scheduleStop(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent stopPendingIntent = buildStopPendingIntent(context);
        long triggerAtMillis = SystemClock.elapsedRealtime() + PLAY_DURATION_MS;
        try {
            setExactAlarm(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, stopPendingIntent);
        } catch (SecurityException e) {
            Log.w(TAG, "scheduleStop: exact alarm permission revoked", e);
        }
    }

    private static void setExactAlarm(AlarmManager alarmManager, int type, long triggerAtMillis, PendingIntent pendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(type, triggerAtMillis, pendingIntent);
        } else {
            alarmManager.setExact(type, triggerAtMillis, pendingIntent);
        }
    }

    public static void cancelAll(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarmManager.cancel(buildStartPendingIntent(context));
        alarmManager.cancel(buildStopPendingIntent(context));
    }

    private static PendingIntent buildStartPendingIntent(Context context) {
        Intent intent = new Intent(context, CalendarAlarmReceiver.class);
        intent.setAction(CalendarAlarmReceiver.ACTION_ALARM_START);
        return PendingIntent.getBroadcast(context, REQUEST_CODE_ALARM_START, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent buildStopPendingIntent(Context context) {
        Intent intent = new Intent(context, CalendarAlarmReceiver.class);
        intent.setAction(CalendarAlarmReceiver.ACTION_ALARM_STOP);
        return PendingIntent.getBroadcast(context, REQUEST_CODE_ALARM_STOP, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
