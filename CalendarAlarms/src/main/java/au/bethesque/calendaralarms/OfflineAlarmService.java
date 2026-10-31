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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

import au.bethesque.calendaralarms.utils.Log;

/**
 * Plays a calendar notification occurrence through the alarm audio stream, for when the
 * snapserver can't be reached (e.g. away from home). Started by an AlarmManager alarm clock
 * at the occurrence's play_datetime (see CalendarAlarmScheduler#scheduleOfflinePlayback).
 * If there are announcements, plays the preannounce chime then speaks each announcement's
 * event name. Then, if there are alarms, loops the alarm tone while repeatedly speaking the
 * alarm event names over it, until the occurrence's duration has elapsed or Stop is tapped.
 */
public class OfflineAlarmService extends Service {
    private static final String TAG = "OfflineAlarmService";

    public static final String ACTION_PLAY = "au.bethesque.calendaralarms.OFFLINE_ALARM_PLAY";
    public static final String ACTION_STOP = "au.bethesque.calendaralarms.OFFLINE_ALARM_STOP";
    public static final String EXTRA_ANNOUNCEMENTS = "announcements";
    public static final String EXTRA_ALARMS = "alarms";
    public static final String EXTRA_DURATION_MILLIS = "duration_millis";
    public static final String EXTRA_TITLE = "title";

    private static final String NOTIFICATION_CHANNEL_ID = "au.bethesque.calendaralarms.alarms";
    private static final int NOTIFICATION_ID = 2101;

    // Pause between repeats of the alarm event names
    private static final long ALARM_SPEECH_GAP_MS = 5000;
    // How long the alarm tone plays before the alarm event names are first spoken
    private static final long ALARM_SPEECH_DELAY_MS = 5000;

    private static final String UTTERANCE_ANNOUNCEMENT = "announcement";
    private static final String UTTERANCE_LAST_ANNOUNCEMENT = "last_announcement";
    private static final String UTTERANCE_ALARM = "alarm";
    private static final String UTTERANCE_ALARM_GAP = "alarm_gap";

    private static final AudioAttributes ALARM_AUDIO_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    private static final AudioAttributes SPEECH_AUDIO_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> announcements = new ArrayList<>();
    private final List<String> alarms = new ArrayList<>();
    private MediaPlayer player;
    private TextToSpeech tts;
    private boolean ttsInitialised;
    private boolean ttsReady;
    // True from ACTION_PLAY until playback is stopped, so late TTS callbacks are ignored
    private boolean playing;
    // ACTION_PLAY arrived before TTS finished initialising
    private boolean startPending;
    private AudioFocusRequest audioFocusRequest;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        tts = new TextToSpeech(this, this::onTtsInit);
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
            resetPlayback();
            announcements.clear();
            alarms.clear();
            ArrayList<String> announcementNames = intent.getStringArrayListExtra(EXTRA_ANNOUNCEMENTS);
            if (announcementNames != null)
                announcements.addAll(announcementNames);
            ArrayList<String> alarmNames = intent.getStringArrayListExtra(EXTRA_ALARMS);
            if (alarmNames != null)
                alarms.addAll(alarmNames);

            playing = true;
            requestAudioFocus();
            long durationMillis = intent.getLongExtra(EXTRA_DURATION_MILLIS, 0);
            if (durationMillis > 0)
                handler.postDelayed(this::stopPlayback, durationMillis);

            if (ttsInitialised)
                startSequence();
            else
                startPending = true;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        resetPlayback();
        Settings.getInstance(this).clearAlarmMode(Settings.AlarmMode.OFFLINE);
        tts.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void onTtsInit(int status) {
        ttsInitialised = true;
        if (status == TextToSpeech.SUCCESS) {
            tts.setAudioAttributes(SPEECH_AUDIO_ATTRIBUTES);
            tts.setOnUtteranceProgressListener(new SpeechListener());
            ttsReady = true;
        } else {
            Log.w(TAG, "onTtsInit: text to speech unavailable (status=" + status + "), event names won't be spoken");
        }

        if (startPending) {
            startPending = false;
            startSequence();
        }
    }

    private void startSequence() {
        if (!announcements.isEmpty())
            playPreannounce();
        else
            startAlarms();
    }

    private void playPreannounce() {
        Log.i(TAG, "playPreannounce: announcements " + announcements);
        player = createPlayer(R.raw.preannounce);
        if (player == null) {
            speakAnnouncements();
            return;
        }
        player.setOnCompletionListener(mp -> speakAnnouncements());
        player.setOnErrorListener((mp, what, extra) -> {
            Log.w(TAG, "playPreannounce: error playing preannounce (what=" + what + ", extra=" + extra + ")");
            speakAnnouncements();
            return true;
        });
        player.start();
    }

    private void speakAnnouncements() {
        releasePlayer();
        if (!ttsReady) {
            startAlarms();
            return;
        }
        speakNames(announcements, UTTERANCE_ANNOUNCEMENT, UTTERANCE_LAST_ANNOUNCEMENT);
    }

    private void startAlarms() {
        releasePlayer();
        if (alarms.isEmpty()) {
            Log.i(TAG, "startAlarms: no alarms, finished playing");
            stopPlayback();
            return;
        }

        Log.i(TAG, "startAlarms: alarms " + alarms);
        player = createPlayer(R.raw.early_riser);
        if (player != null) {
            player.setLooping(true);
            player.start();
        }
        if (ttsReady)
            handler.postDelayed(this::speakAlarms, ALARM_SPEECH_DELAY_MS);
    }

    private void speakAlarms() {
        speakNames(alarms, UTTERANCE_ALARM, UTTERANCE_ALARM);
        tts.playSilentUtterance(ALARM_SPEECH_GAP_MS, TextToSpeech.QUEUE_ADD, UTTERANCE_ALARM_GAP);
    }

    private void speakNames(List<String> names, String utteranceId, String lastUtteranceId) {
        for (int i = 0; i < names.size(); i++) {
            String id = i == names.size() - 1 ? lastUtteranceId : utteranceId;
            tts.speak(names.get(i), TextToSpeech.QUEUE_ADD, null, id);
        }
    }

    private MediaPlayer createPlayer(int resId) {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        MediaPlayer mediaPlayer = MediaPlayer.create(this, resId, ALARM_AUDIO_ATTRIBUTES, audioManager.generateAudioSessionId());
        if (mediaPlayer == null) {
            Log.w(TAG, "createPlayer: could not load " + getResources().getResourceEntryName(resId));
            return null;
        }
        mediaPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        return mediaPlayer;
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    /**
     * Stops all sound and pending callbacks, leaving the service running.
     */
    private void resetPlayback() {
        playing = false;
        startPending = false;
        handler.removeCallbacksAndMessages(null);
        if (ttsReady)
            tts.stop();
        releasePlayer();
        abandonAudioFocus();
    }

    private void stopPlayback() {
        resetPlayback();
        Settings.getInstance(this).clearAlarmMode(Settings.AlarmMode.OFFLINE);
        stopForeground(true);
        stopSelf();
    }

    /**
     * Moves on from the announcements to the alarms, or repeats the alarm names, as each
     * finishes. Callbacks arrive on a binder thread, so they're handed to the main thread.
     */
    private class SpeechListener extends UtteranceProgressListener {
        @Override
        public void onStart(String utteranceId) {
        }

        @Override
        public void onDone(String utteranceId) {
            handler.post(() -> {
                if (!playing)
                    return;
                if (UTTERANCE_LAST_ANNOUNCEMENT.equals(utteranceId))
                    startAlarms();
                else if (UTTERANCE_ALARM_GAP.equals(utteranceId))
                    speakAlarms();
            });
        }

        @Override
        public void onError(String utteranceId) {
            Log.w(TAG, "onError: could not speak utterance " + utteranceId);
            onDone(utteranceId);
        }
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
