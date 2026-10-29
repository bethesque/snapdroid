package au.bethesque.calendaralarms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowAlarmManager;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class CalendarAlarmSchedulerTest {
    private static final long LEAD_TIME_MS = 45_000L;
    private static final Instant NOW = Instant.parse("2026-11-02T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private Context context;
    private ShadowAlarmManager shadowAlarmManager;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        shadowAlarmManager = shadowOf((AlarmManager) context.getSystemService(Context.ALARM_SERVICE));
        ShadowAlarmManager.setCanScheduleExactAlarms(true);
    }

    private static String entry(String summary, String type, Instant playAt, int durationSeconds) {
        return "{\"event\":{\"summary\":\"" + summary + "\"},\"type\":\"" + type + "\","
                + "\"play_datetime\":\"" + OffsetDateTime.ofInstant(playAt, ZoneOffset.UTC) + "\","
                + "\"duration_seconds\":" + durationSeconds + "}";
    }

    private void storeFeed(String... entries) {
        Settings.getInstance(context).setCalendarNotificationsJson(
                "{\"notifications\":[" + String.join(",", entries) + "]}");
    }

    private static List<String> summaries(List<CalendarNotification> notifications) {
        List<String> result = new ArrayList<>();
        for (CalendarNotification notification : notifications)
            result.add(notification.getSummary());
        return result;
    }

    @Test
    public void loadStoredReturnsEmptyWhenNothingStored() {
        assertTrue(CalendarAlarmScheduler.loadStored(context).isEmpty());
    }

    @Test
    public void loadStoredReturnsEmptyWhenJsonIsMalformed() {
        Settings.getInstance(context).setCalendarNotificationsJson("not json");

        assertTrue(CalendarAlarmScheduler.loadStored(context).isEmpty());
    }

    @Test
    public void loadStoredSkipsMalformedEntries() {
        storeFeed(entry("Good", "alarm", NOW, 60), "{\"event\":{}}", entry("Also good", "alarm", NOW, 60));

        assertEquals(Arrays.asList("Good", "Also good"), summaries(CalendarAlarmScheduler.loadStored(context)));
    }

    @Test
    public void getNextReturnsEarliestAfterTheGivenInstant() {
        storeFeed(
                entry("Past", "alarm", NOW.minusSeconds(60), 60),
                entry("Later", "alarm", NOW.plusSeconds(600), 60),
                entry("Soonest", "alarm", NOW.plusSeconds(60), 60));

        assertEquals("Soonest", CalendarAlarmScheduler.getNext(context, NOW).getSummary());
    }

    @Test
    public void getNextReturnsNullWhenNothingUpcoming() {
        storeFeed(entry("Past", "alarm", NOW.minusSeconds(60), 60), entry("Now", "alarm", NOW, 60));

        assertNull(CalendarAlarmScheduler.getNext(context, NOW));
    }

    @Test
    public void getNextGroupReturnsEveryEntrySharingTheNextPlayTime() {
        storeFeed(
                entry("First", "alarm", NOW.plusSeconds(60), 60),
                entry("Later", "alarm", NOW.plusSeconds(600), 60),
                entry("Second", "announce", NOW.plusSeconds(60), 60));

        assertEquals(Arrays.asList("First", "Second"), summaries(CalendarAlarmScheduler.getNextGroup(context, NOW)));
    }

    @Test
    public void getNextPlayDurationMillisIsLongestInGroupPlusLeadTime() {
        Instant playAt = NOW.plusSeconds(3600);
        storeFeed(entry("Short", "alarm", playAt, 60), entry("Long", "announce", playAt, 120));

        assertEquals(120_000L + LEAD_TIME_MS, CalendarAlarmScheduler.getNextPlayDurationMillis(context, CLOCK));
    }

    @Test
    public void scheduleNextArmsExactAlarmLeadTimeBeforePlay() {
        Instant playAt = NOW.plusSeconds(3600);
        storeFeed(entry("Standup", "alarm", playAt, 60));

        assertTrue(CalendarAlarmScheduler.scheduleNext(context, CLOCK));

        ShadowAlarmManager.ScheduledAlarm alarm = shadowAlarmManager.peekNextScheduledAlarm();
        assertNotNull(alarm);
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.getType());
        assertEquals(playAt.toEpochMilli() - LEAD_TIME_MS, alarm.getTriggerAtMs());
        assertEquals(CalendarAlarmReceiver.ACTION_ALARM_START,
                shadowOf(alarm.operation).getSavedIntent().getAction());
    }

    @Test
    public void scheduleNextFiresImmediatelyWhenInsideLeadTime() {
        storeFeed(entry("Standup", "alarm", NOW.plusSeconds(10), 60));

        CalendarAlarmScheduler.scheduleNext(context, CLOCK);

        assertEquals(NOW.toEpochMilli(), shadowAlarmManager.peekNextScheduledAlarm().getTriggerAtMs());
    }

    @Test
    public void scheduleNextReplacesThePreviouslyArmedAlarm() {
        Instant playAt = NOW.plusSeconds(3600);
        storeFeed(entry("Standup", "alarm", playAt, 60));
        CalendarAlarmScheduler.scheduleNext(context, CLOCK);
        CalendarAlarmScheduler.scheduleNext(context, CLOCK);

        assertEquals(1, shadowAlarmManager.getScheduledAlarms().size());
    }

    @Test
    public void scheduleNextReturnsFalseAndArmsNothingWhenNothingUpcoming() {
        storeFeed(entry("Past", "alarm", NOW.minusSeconds(60), 60));

        assertFalse(CalendarAlarmScheduler.scheduleNext(context, CLOCK));
        assertNull(shadowAlarmManager.peekNextScheduledAlarm());
    }

    @Test
    public void scheduleNextDoesNotArmWithoutExactAlarmPermission() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false);
        storeFeed(entry("Standup", "alarm", NOW.plusSeconds(3600), 60));

        assertTrue(CalendarAlarmScheduler.scheduleNext(context, CLOCK));
        assertNull(shadowAlarmManager.peekNextScheduledAlarm());
    }

    @Test
    public void scheduleOfflinePlaybackSetsAlarmClockForGroupWithAnnouncementsFirst() {
        Instant playAt = NOW.plusSeconds(3600);
        storeFeed(
                entry("Standup", "alarm", playAt, 60),
                entry("Bins", "announce", playAt, 120),
                entry("Later", "alarm", playAt.plusSeconds(600), 60));

        assertTrue(CalendarAlarmScheduler.scheduleOfflinePlayback(context, CLOCK));

        ShadowAlarmManager.ScheduledAlarm alarm = shadowAlarmManager.peekNextScheduledAlarm();
        assertNotNull(alarm.getAlarmClockInfo());
        assertEquals(playAt.toEpochMilli(), alarm.getTriggerAtMs());

        Intent intent = shadowOf(alarm.operation).getSavedIntent();
        assertEquals(OfflineAlarmService.ACTION_PLAY, intent.getAction());
        assertEquals(Arrays.asList("It's time for Bins"),
                intent.getStringArrayListExtra(OfflineAlarmService.EXTRA_ANNOUNCEMENTS));
        assertEquals(Arrays.asList("It's time for Standup"),
                intent.getStringArrayListExtra(OfflineAlarmService.EXTRA_ALARMS));
        assertEquals(120_000L, intent.getLongExtra(OfflineAlarmService.EXTRA_DURATION_MILLIS, 0));
        assertEquals("Bins, Standup", intent.getStringExtra(OfflineAlarmService.EXTRA_TITLE));
    }

    @Test
    public void scheduleOfflinePlaybackReturnsFalseWhenNothingUpcoming() {
        assertFalse(CalendarAlarmScheduler.scheduleOfflinePlayback(context, CLOCK));
        assertNull(shadowAlarmManager.peekNextScheduledAlarm());
    }

    @Test
    public void cancelAllRemovesEveryArmedAlarm() {
        storeFeed(entry("Standup", "alarm", NOW.plusSeconds(3600), 60));
        CalendarAlarmScheduler.scheduleNext(context, CLOCK);
        CalendarAlarmScheduler.scheduleStop(context, 60_000L);
        CalendarAlarmScheduler.scheduleOfflinePlayback(context, CLOCK);

        CalendarAlarmScheduler.cancelAll(context);

        assertTrue(shadowAlarmManager.getScheduledAlarms().isEmpty());
    }
}
