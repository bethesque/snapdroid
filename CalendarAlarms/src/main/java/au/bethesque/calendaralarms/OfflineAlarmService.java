package au.bethesque.calendaralarms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

import au.bethesque.calendaralarms.utils.Log;
import de.badaix.snapcast.R;

/**
 * Plays the downloaded audio files for a calendar notification occurrence through the
 * alarm audio stream, for when the snapserver can't be reached (e.g. away from home).
 * Started by an AlarmManager alarm clock at the occurrence's play_datetime (see
 * CalendarAlarmScheduler#scheduleOfflinePlayback), plays each file once in order, then stops.
 */
public class OfflineAlarmService extends Service {
    private static final String TAG = "OfflineAlarmService";

    public static final String ACTION_PLAY = "au.bethesque.calendaralarms.OFFLINE_ALARM_PLAY";
    public static final String ACTION_STOP = "au.bethesque.calendaralarms.OFFLINE_ALARM_STOP";
    public static final String EXTRA_AUDIO_URIS = "audio_uris";
    public static final String EXTRA_TITLE = "title";

    private static final String NOTIFICATION_CHANNEL_ID = "au.bethesque.calendaralarms.alarms";
    private static final int NOTIFICATION_ID = 2101;

    private static final AudioAttributes ALARM_AUDIO_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    private final List<Uri> queue = new ArrayList<>();
    private MediaPlayer player;
    private AudioFocusRequest audioFocusRequest;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopPlayback();
            return START_NOT_STICKY;
        }

        if (ACTION_PLAY.equals(intent.getAction())) {
            startAlarmForeground(intent.getStringExtra(EXTRA_TITLE));

            // Replace anything still playing from a previous occurrence
            releasePlayer();
            queue.clear();
            ArrayList<String> uris = intent.getStringArrayListExtra(EXTRA_AUDIO_URIS);
            if (uris != null) {
                for (String uri : uris)
                    queue.add(Uri.parse(uri));
            }

            requestAudioFocus();
            playNext();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        abandonAudioFocus();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void playNext() {
        releasePlayer();
        if (queue.isEmpty()) {
            Log.i(TAG, "playNext: finished playing");
            stopPlayback();
            return;
        }

        Uri uri = queue.remove(0);
        Log.i(TAG, "playNext: playing " + uri);
        player = new MediaPlayer();
        player.setAudioAttributes(ALARM_AUDIO_ATTRIBUTES);
        player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        player.setOnCompletionListener(mp -> playNext());
        player.setOnErrorListener((mp, what, extra) -> {
            Log.w(TAG, "playNext: error playing " + uri + " (what=" + what + ", extra=" + extra + ")");
            playNext();
            return true;
        });
        try {
            player.setDataSource(this, uri);
            player.setOnPreparedListener(MediaPlayer::start);
            player.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "playNext: could not play " + uri, e);
            playNext();
        }
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void stopPlayback() {
        queue.clear();
        releasePlayer();
        abandonAudioFocus();
        stopForeground(true);
        stopSelf();
    }

    private void requestAudioFocus() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(ALARM_AUDIO_ATTRIBUTES)
                    .build();
            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }
    }

    private void abandonAudioFocus() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
                audioFocusRequest = null;
            }
        } else {
            audioManager.abandonAudioFocus(null);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return;

        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                getString(R.string.offline_alarm_channel_name), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(getString(R.string.offline_alarm_channel_description));
        // The audio is played by this service on the alarm stream, not by the notification
        channel.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void startAlarmForeground(String title) {
        Intent stopIntent = new Intent(this, OfflineAlarmService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent piStop = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // MainActivity is the root, so reuse the existing instance rather than stacking a new one
        Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piMain = PendingIntent.getActivity(this, 0, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title != null ? title : getString(R.string.app_name))
                .setContentText(getString(R.string.offline_alarm_text))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(piMain)
                .setFullScreenIntent(piMain, true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.offline_alarm_stop), piStop)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }
}
