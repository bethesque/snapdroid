package au.bethesque.calendaralarms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import androidx.core.app.NotificationCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.ShadowTextToSpeech;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class OfflineAlarmServiceTest {
    private static final Duration ALARM_SPEECH_DELAY = Duration.ofSeconds(5);
    private static final int MEDIA_DURATION_MS = 10 * 60_000;

    private Context context;
    // Every MediaPlayer the service creates, in order (the preannounce chime, then the alarm tone)
    private final List<MediaPlayer> players = new ArrayList<>();
    private ServiceController<OfflineAlarmService> controller;
    private OfflineAlarmService service;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        // Longer than any test idles for: the shadow spins forever looping short media,
        // and tests finish the chime themselves with invokeCompletionListener
        ShadowMediaPlayer.setMediaInfoProvider(dataSource -> new ShadowMediaPlayer.MediaInfo(MEDIA_DURATION_MS, 0));
        ShadowMediaPlayer.setCreateListener((player, shadow) -> players.add(player));
        controller = Robolectric.buildService(OfflineAlarmService.class).create();
        service = controller.get();
    }

    private static Intent playIntent(List<String> announcements, List<String> alarms, long durationMillis, String title) {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), OfflineAlarmService.class);
        intent.setAction(OfflineAlarmService.ACTION_PLAY);
        intent.putStringArrayListExtra(OfflineAlarmService.EXTRA_ANNOUNCEMENTS, new ArrayList<>(announcements));
        intent.putStringArrayListExtra(OfflineAlarmService.EXTRA_ALARMS, new ArrayList<>(alarms));
        intent.putExtra(OfflineAlarmService.EXTRA_DURATION_MILLIS, durationMillis);
        if (title != null)
            intent.putExtra(OfflineAlarmService.EXTRA_TITLE, title);
        return intent;
    }

    private void play(List<String> announcements, List<String> alarms) {
        start(playIntent(announcements, alarms, 60_000L, "Bins, Standup"));
    }

    private void start(Intent intent) {
        controller.withIntent(intent).startCommand(0, 1);
        idleMainLooper();
    }

    private void stop() {
        start(new Intent(context, OfflineAlarmService.class).setAction(OfflineAlarmService.ACTION_STOP));
    }

    private void initTts(int status) {
        tts().getOnInitListener().onInit(status);
        idleMainLooper();
    }

    private static ShadowTextToSpeech tts() {
        return shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance());
    }

    private static void idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static boolean isPlaying(MediaPlayer player) {
        return shadowOf(player).getState() == ShadowMediaPlayer.State.STARTED;
    }

    private static boolean isReleased(MediaPlayer player) {
        return shadowOf(player).getState() == ShadowMediaPlayer.State.END;
    }

    @Test
    public void playShowsOngoingAlarmNotificationWithTitleAndStopButton() {
        initTts(TextToSpeech.SUCCESS);
        play(Arrays.asList("Bins"), Arrays.asList("Standup"));

        Notification notification = shadowOf(service).getLastForegroundNotification();
        assertNotNull(notification);
        assertEquals("Bins, Standup", notification.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("Playing alarm", notification.extras.getString(Notification.EXTRA_TEXT));
        assertEquals(NotificationCompat.CATEGORY_ALARM, notification.category);
        assertTrue((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertNotNull(notification.fullScreenIntent);

        assertEquals(1, notification.actions.length);
        assertEquals("Stop", notification.actions[0].title.toString());
        assertEquals(OfflineAlarmService.ACTION_STOP,
                shadowOf(notification.actions[0].actionIntent).getSavedIntent().getAction());
    }

    @Test
    public void playUsesAppNameAsNotificationTitleWhenNoneGiven() {
        initTts(TextToSpeech.SUCCESS);
        start(playIntent(Arrays.asList(), Arrays.asList("Standup"), 60_000L, null));

        assertEquals("Calendar Alarms",
                shadowOf(service).getLastForegroundNotification().extras.getString(Notification.EXTRA_TITLE));
    }

    @Test
    public void playRequestsTransientAudioFocus() {
        initTts(TextToSpeech.SUCCESS);
        play(Arrays.asList(), Arrays.asList("Standup"));

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                shadowOf(audioManager).getLastAudioFocusRequest().durationHint);
    }

    @Test
    public void announcementsPlayChimeThenSpeakNamesThenLoopAlarmTone() {
        initTts(TextToSpeech.SUCCESS);
        play(Arrays.asList("Bins", "Recycling"), Arrays.asList("Standup"));

        MediaPlayer chime = players.get(0);
        assertTrue(isPlaying(chime));
        assertTrue(tts().getSpokenTextList().isEmpty());

        // The shadow TTS reports each utterance done straight away, moving on to the alarms
        shadowOf(chime).invokeCompletionListener();
        idleMainLooper();

        assertTrue(isReleased(chime));
        assertEquals(Arrays.asList("Bins", "Recycling"), tts().getSpokenTextList());
        MediaPlayer alarmTone = players.get(1);
        assertTrue(isPlaying(alarmTone));
        assertTrue(alarmTone.isLooping());
    }

    @Test
    public void alarmNamesAreSpokenOverTheToneAfterADelayAndRepeatAfterEachGap() {
        initTts(TextToSpeech.SUCCESS);
        play(Arrays.asList(), Arrays.asList("Standup", "Lunch"));

        assertEquals(1, players.size());
        assertTrue(isPlaying(players.get(0)));
        assertTrue(tts().getSpokenTextList().isEmpty());

        shadowOf(Looper.getMainLooper()).idleFor(ALARM_SPEECH_DELAY);
        assertEquals(Arrays.asList("Standup", "Lunch"), tts().getSpokenTextList());

        tts().getUtteranceProgressListener().onDone("alarm_gap");
        idleMainLooper();
        assertEquals(Arrays.asList("Standup", "Lunch", "Standup", "Lunch"), tts().getSpokenTextList());
    }

    @Test
    public void announcementsWithoutAlarmsStopOnceSpoken() {
        initTts(TextToSpeech.SUCCESS);
        play(Arrays.asList("Bins"), Arrays.asList());

        shadowOf(players.get(0)).invokeCompletionListener();
        idleMainLooper();

        assertEquals(1, players.size());
        assertTrue(shadowOf(service).isStoppedBySelf());
    }

    @Test
    public void playWaitsForTextToSpeechToInitialise() {
        play(Arrays.asList(), Arrays.asList("Standup"));
        assertTrue(players.isEmpty());

        initTts(TextToSpeech.SUCCESS);

        assertEquals(1, players.size());
        assertTrue(isPlaying(players.get(0)));
    }

    @Test
    public void withoutTextToSpeechTheChimeAndAlarmTonePlayWithoutSpeech() {
        initTts(TextToSpeech.ERROR);
        play(Arrays.asList("Bins"), Arrays.asList("Standup"));

        shadowOf(players.get(0)).invokeCompletionListener();
        shadowOf(Looper.getMainLooper()).idleFor(ALARM_SPEECH_DELAY);

        assertTrue(isPlaying(players.get(1)));
        assertTrue(tts().getSpokenTextList().isEmpty());
    }

    @Test
    public void stopActionStopsSoundAndServiceAndClearsOfflineAlarmMode() {
        Settings.getInstance(context).setAlarmMode(Settings.AlarmMode.OFFLINE);
        initTts(TextToSpeech.SUCCESS);
        play(Arrays.asList(), Arrays.asList("Standup"));

        stop();

        assertTrue(isReleased(players.get(0)));
        assertTrue(tts().isStopped());
        assertTrue(shadowOf(service).isForegroundStopped());
        assertTrue(shadowOf(service).isStoppedBySelf());
        assertEquals(Settings.AlarmMode.NONE, Settings.getInstance(context).getAlarmMode());
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        assertNotNull(shadowOf(audioManager).getLastAbandonedAudioFocusRequest());
    }

    @Test
    public void stopsOnceTheDurationHasElapsed() {
        initTts(TextToSpeech.SUCCESS);
        start(playIntent(Arrays.asList(), Arrays.asList("Standup"), 60_000L, null));

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(59));
        assertFalse(shadowOf(service).isStoppedBySelf());

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertTrue(shadowOf(service).isStoppedBySelf());
        assertTrue(isReleased(players.get(0)));
    }

    @Test
    public void speechFinishingAfterStopDoesNotRestartTheAlarmTone() {
        initTts(TextToSpeech.SUCCESS);
        play(Arrays.asList("Bins"), Arrays.asList("Standup"));
        stop();

        tts().getUtteranceProgressListener().onDone("last_announcement");
        idleMainLooper();

        assertEquals(1, players.size());
    }

    @Test
    public void playReplacesAnOccurrenceThatIsStillPlaying() {
        initTts(TextToSpeech.SUCCESS);
        play(Arrays.asList(), Arrays.asList("Standup"));

        play(Arrays.asList(), Arrays.asList("Lunch"));
        shadowOf(Looper.getMainLooper()).idleFor(ALARM_SPEECH_DELAY);

        assertTrue(isReleased(players.get(0)));
        assertTrue(isPlaying(players.get(1)));
        assertEquals(Arrays.asList("Lunch"), tts().getSpokenTextList());
    }

    @Test
    public void destroyClearsOfflineAlarmModeAndShutsDownTextToSpeech() {
        Settings.getInstance(context).setAlarmMode(Settings.AlarmMode.OFFLINE);

        controller.destroy();

        assertEquals(Settings.AlarmMode.NONE, Settings.getInstance(context).getAlarmMode());
        assertTrue(tts().isShutdown());
    }
}
