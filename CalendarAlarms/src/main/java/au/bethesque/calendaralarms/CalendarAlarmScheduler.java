package au.bethesque.calendaralarms;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import au.bethesque.calendaralarms.utils.Log;


/**
 * Fetches and persists the "Calendar Alarms URL" feed, and drives the AlarmManager
 * schedule that starts the Snapclient 45 seconds before each upcoming play_datetime,
 * lets it run for that occurrence's duration_seconds, then stops it and arms the next one.
 */
public class CalendarAlarmScheduler {
    private static final String TAG = "CalendarAlarmScheduler";

    private static final int REQUEST_CODE_ALARM_START = 2001;
    private static final int REQUEST_CODE_ALARM_STOP = 2002;
    private static final int REQUEST_CODE_OFFLINE_PLAY = 2003;
    private static final int REQUEST_CODE_OFFLINE_SHOW = 2004;
    private static final String TYPE_ANNOUNCE = "announce";
    private static final long LEAD_TIME_MS = 45_000L;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final String NOTIFICATIONS_PATH = "/alarm/notifications";
    private static final String STOP_ALARM_PATH = "/alarm/stop";
    private static final String SNOOZE_ALARM_PATH = "/alarm/snooze";
    private static final int SNAPSERVER_REACHABLE_TIMEOUT_MS = 3_000;
    private static final String PERIODIC_REFRESH_WORK_NAME = "calendar_refresh";
    private static final String AUDIO_DIR = "audio";

    /**
     * WorkManager silently refuses to run PeriodicWorkRequests more often than this, so
     * it's also the floor enforced when saving the setting in ServerSettingsActivity.
     */
    public static final int MIN_REFRESH_INTERVAL_MINUTES =
            (int) TimeUnit.MILLISECONDS.toMinutes(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS);

    private CalendarAlarmScheduler() {
    }

    public static void fetchAndStore(Context context, Runnable onSuccess, Consumer<Exception> onError) {
        Context appContext = context.getApplicationContext();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                fetchAndStoreSync(appContext);
                mainHandler.post(onSuccess);
            } catch (Exception e) {
                Log.e(TAG, "fetchAndStore failed", e);
                mainHandler.post(() -> onError.accept(e));
            }
        }).start();
    }

    /**
     * Blocking fetch-and-persist, for callers (e.g. a WorkManager Worker) that already
     * run on a background thread and want to handle the result/exception synchronously.
     */
    public static void fetchAndStoreSync(Context context) throws IOException, JSONException {
        Context appContext = context.getApplicationContext();
        String baseUrl = Settings.getInstance(appContext).getCalendarAlarmsUrl();
        String body = fetch(baseUrl.replaceAll("/+$", "") + NOTIFICATIONS_PATH);
        // Sanity-parse before persisting so garbage never overwrites a working schedule.
        new JSONObject(body);
        Settings.getInstance(appContext).setCalendarNotificationsJson(body);
        Log.i(TAG, "fetchAndStoreSync: refreshed " + loadStored(appContext).size() + " events from " + baseUrl);
        downloadAudioFiles(appContext);
    }

    /**
     * Downloads each stored notification's audio_file_url into local storage, so it can be
     * played later when the snapserver is unreachable. Failures are logged rather than
     * thrown so a missing audio file never blocks the schedule from updating.
     */
    private static void downloadAudioFiles(Context context) {
        for (CalendarNotification notification : loadStored(context)) {
            String audioFileUrl = notification.getAudioFileUrl();
            if (audioFileUrl.isEmpty())
                continue;

            try {
                downloadAudioFile(context, audioFileUrl);
            } catch (IOException e) {
                Log.w(TAG, "downloadAudioFiles: failed to download " + audioFileUrl, e);
            }
        }
    }

    /**
     * Returns the local file an audio_file_url is (or will be) downloaded to.
     */
    public static File getLocalAudioFile(Context context, String audioFileUrl) {
        String name = Uri.parse(audioFileUrl).getLastPathSegment();
        return new File(new File(context.getFilesDir(), AUDIO_DIR), name);
    }

    private static void downloadAudioFile(Context context, String audioFileUrl) throws IOException {
        File target = getLocalAudioFile(context, audioFileUrl);
        // Spike: the audio URL is currently static, so an existing file is assumed current.
        if (target.exists()) {
            Log.d(TAG, "downloadAudioFile: " + target + " already exists, skipping " + audioFileUrl);
            return;
        }

        URL url = new URL(audioFileUrl);
        File dir = target.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Could not create " + dir);
        // Download to a temp file first so an interrupted download never looks complete.
        File tmp = new File(target.getPath() + ".tmp");

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Unexpected HTTP response code: " + responseCode);
            }

            try (InputStream is = connection.getInputStream();
                 OutputStream os = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
            if (!tmp.renameTo(target)) {
                throw new IOException("Could not rename " + tmp + " to " + target);
            }
            Log.d(TAG, "downloadAudioFile: downloaded " + url + " to " + target);
        } finally {
            connection.disconnect();
            tmp.delete();
        }
    }

    /**
     * (Re)schedules the periodic calendar feed refresh at the interval configured in
     * Settings. Safe to call repeatedly (e.g. on every app start and whenever the
     * interval setting changes) since WorkManager de-dupes on the unique work name.
     */
    public static void schedulePeriodicRefresh(Context context) {
        Context appContext = context.getApplicationContext();
        int intervalMinutes = Math.max(Settings.getInstance(appContext).getCalendarRefreshIntervalMinutes(), MIN_REFRESH_INTERVAL_MINUTES);

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                CalendarRefreshWorker.class, intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, PeriodicWorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build();

        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                PERIODIC_REFRESH_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    /**
     * Posts to the Calendar Alarms URL's /alarm/stop endpoint, telling the server to stop
     * the alarm for all listening clients (as opposed to just this phone).
     */
    public static void stopOnServer(Context context, Runnable onSuccess, Consumer<Exception> onError) {
        Context appContext = context.getApplicationContext();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                String baseUrl = Settings.getInstance(appContext).getCalendarAlarmsUrl();
                post(baseUrl.replaceAll("/+$", "") + STOP_ALARM_PATH);
                mainHandler.post(onSuccess);
            } catch (Exception e) {
                Log.e(TAG, "stopOnServer failed", e);
                mainHandler.post(() -> onError.accept(e));
            }
        }).start();
    }

    /**
     * Posts to the Calendar Alarms URL's /alarm/snooze endpoint, telling the server to
     * snooze the alarm for all listening clients.
     */
    public static void snoozeOnServer(Context context, Runnable onSuccess, Consumer<Exception> onError) {
        Context appContext = context.getApplicationContext();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                String baseUrl = Settings.getInstance(appContext).getCalendarAlarmsUrl();
                post(baseUrl.replaceAll("/+$", "") + SNOOZE_ALARM_PATH);
                mainHandler.post(onSuccess);
            } catch (Exception e) {
                Log.e(TAG, "snoozeOnServer failed", e);
                mainHandler.post(() -> onError.accept(e));
            }
        }).start();
    }

    private static void post(String urlString) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("Unexpected HTTP response code: " + responseCode);
            }
        } finally {
            connection.disconnect();
        }
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

    public static boolean isSnapclientEnabled(Context context) {
        return Settings.getInstance(context).isSnapclientEnabled();
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
            Log.i(TAG, "isSnapserverReachable: " + host + " unreachable", e);
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
     * Returns every stored notification that shares the earliest upcoming play_datetime
     * (i.e. all events due to play next, since more than one calendar event can share the
     * same play time), or an empty list if there's nothing upcoming.
     */
    public static List<CalendarNotification> getNextGroup(Context context) {
        List<CalendarNotification> group = new ArrayList<>();
        CalendarNotification next = getNext(context);
        if (next == null)
            return group;

        Instant nextInstant = next.getPlayDateTime().toInstant();
        for (CalendarNotification notification : loadStored(context)) {
            if (notification.getPlayDateTime().toInstant().equals(nextInstant))
                group.add(notification);
        }
        return group;
    }

    /**
     * Returns how long the Snapclient should keep running for the upcoming occurrence(s),
     * i.e. the longest duration_seconds among every notification sharing the next
     * play_datetime plus the lead time it was started early by, or 0 if there's nothing
     * upcoming.
     */
    public static long getNextPlayDurationMillis(Context context) {
        long maxDurationSeconds = 0;
        for (CalendarNotification notification : getNextGroup(context)) {
            maxDurationSeconds = Math.max(maxDurationSeconds, notification.getDurationSeconds());
        }
        return maxDurationSeconds * 1000L + LEAD_TIME_MS;
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
            Log.i(TAG, "scheduleNext: no upcoming notifications, nothing scheduled");
            return false;
        }

        long triggerAtMillis = Math.max(System.currentTimeMillis(), next.getPlayDateTime().toInstant().toEpochMilli() - LEAD_TIME_MS);
        Log.i(TAG, "scheduleNext: \"" + next.getSummary() + "\" at " + next.getPlayDateTime()
                + ", waking up at " + OffsetDateTime.ofInstant(Instant.ofEpochMilli(triggerAtMillis), ZoneId.systemDefault()));

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

    public static void scheduleStop(Context context, long durationMillis) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent stopPendingIntent = buildStopPendingIntent(context);
        long triggerAtMillis = SystemClock.elapsedRealtime() + durationMillis;
        Log.i(TAG, "scheduleStop: waking up at " + OffsetDateTime.ofInstant(Instant.now().plusMillis(durationMillis), ZoneId.systemDefault()));
        try {
            setExactAlarm(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, stopPendingIntent);
        } catch (SecurityException e) {
            Log.w(TAG, "scheduleStop: exact alarm permission revoked", e);
        }
    }

    /**
     * Schedules an alarm clock at the next play_datetime that plays each of that occurrence's
     * downloaded audio files once through OfflineAlarmService, announcements first. A
     * notification whose audio file hasn't been downloaded plays the default alarm sound.
     */
    public static void scheduleOfflinePlayback(Context context) {
        List<CalendarNotification> group = getNextGroup(context);
        if (group.isEmpty())
            return;

        List<CalendarNotification> ordered = new ArrayList<>();
        for (CalendarNotification notification : group) {
            if (TYPE_ANNOUNCE.equals(notification.getType()))
                ordered.add(notification);
        }
        for (CalendarNotification notification : group) {
            if (!TYPE_ANNOUNCE.equals(notification.getType()))
                ordered.add(notification);
        }

        ArrayList<String> audioUris = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        for (CalendarNotification notification : ordered) {
            summaries.add(notification.getSummary());
            Uri audioUri = null;
            if (!notification.getAudioFileUrl().isEmpty()) {
                File audioFile = getLocalAudioFile(context, notification.getAudioFileUrl());
                if (audioFile.exists())
                    audioUri = Uri.fromFile(audioFile);
            }
            if (audioUri == null) {
                Log.w(TAG, "scheduleOfflinePlayback: no audio file for \"" + notification.getSummary() + "\", using default alarm sound");
                audioUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            }
            if (audioUri != null)
                audioUris.add(audioUri.toString());
        }

        Intent intent = new Intent(context, OfflineAlarmService.class);
        intent.setAction(OfflineAlarmService.ACTION_PLAY);
        intent.putStringArrayListExtra(OfflineAlarmService.EXTRA_AUDIO_URIS, audioUris);
        intent.putExtra(OfflineAlarmService.EXTRA_TITLE, String.join(", ", summaries));

        long triggerAtMillis = ordered.get(0).getPlayDateTime().toInstant().toEpochMilli();
        Log.i(TAG, "scheduleOfflinePlayback: playing " + audioUris + " at " + ordered.get(0).getPlayDateTime());
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        try {
            alarmManager.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAtMillis, buildOfflineShowPendingIntent(context)),
                    buildOfflinePlayPendingIntent(context, intent));
        } catch (SecurityException e) {
            Log.w(TAG, "scheduleOfflinePlayback: exact alarm permission revoked", e);
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
        alarmManager.cancel(buildOfflinePlayPendingIntent(context, new Intent(context, OfflineAlarmService.class)
                .setAction(OfflineAlarmService.ACTION_PLAY)));
    }

    private static PendingIntent buildOfflinePlayPendingIntent(Context context, Intent intent) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(context, REQUEST_CODE_OFFLINE_PLAY, intent, flags);
        }
        return PendingIntent.getService(context, REQUEST_CODE_OFFLINE_PLAY, intent, flags);
    }

    private static PendingIntent buildOfflineShowPendingIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, REQUEST_CODE_OFFLINE_SHOW, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
