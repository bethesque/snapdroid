package au.bethesque.calendaralarms;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.time.OffsetDateTime;

// Robolectric provides a working org.json (the Android stubs throw in plain JVM tests)
@RunWith(RobolectricTestRunner.class)
public class CalendarNotificationTest {
    private static final String START_TIME = "2026-11-02T09:00:00+11:00";
    private static final String TEMPLATED_SENTENCES = "["
            + "{\"template\":\"Standup in {mins} minutes.\",\"min\":2,\"max\":null},"
            + "{\"template\":\"Standup is starting.\",\"min\":0,\"max\":2},"
            + "{\"template\":\"Standup started {mins} minutes ago.\",\"min\":null,\"max\":0}"
            + "]";

    private static CalendarNotification notification(String json) throws Exception {
        return new CalendarNotification(new JSONObject(json));
    }

    private static CalendarNotification standup() throws Exception {
        return notification("{\"event\":{\"summary\":\"Standup\",\"start_time\":\"" + START_TIME + "\"},"
                + "\"type\":\"announce\",\"play_datetime\":\"2026-11-02T08:50:00+11:00\","
                + "\"duration_seconds\":60,\"templated_sentences\":" + TEMPLATED_SENTENCES + "}");
    }

    @Test
    public void parsesFields() throws Exception {
        CalendarNotification notification = standup();

        assertEquals("Standup", notification.getSummary());
        assertEquals("announce", notification.getType());
        assertEquals(OffsetDateTime.parse("2026-11-02T08:50:00+11:00"), notification.getPlayDateTime());
        assertEquals(60, notification.getDurationSeconds());
    }

    @Test
    public void defaultsDurationAndTypeWhenMissing() throws Exception {
        CalendarNotification notification = notification(
                "{\"event\":{\"summary\":\"Standup\"},\"play_datetime\":\"2026-11-02T08:50:00+11:00\"}");

        assertEquals(300, notification.getDurationSeconds());
        assertEquals("", notification.getType());
    }

    @Test
    public void spokenTextUsesTemplateMatchingMinutesUntilStart() throws Exception {
        assertEquals("Standup in 10 minutes.",
                standup().getSpokenText(OffsetDateTime.parse("2026-11-02T08:50:00+11:00")));
    }

    @Test
    public void spokenTextMinRangeIsInclusiveAndMaxIsExclusive() throws Exception {
        assertEquals("Standup in 2 minutes.",
                standup().getSpokenText(OffsetDateTime.parse("2026-11-02T08:58:00+11:00")));
        assertEquals("Standup is starting.",
                standup().getSpokenText(OffsetDateTime.parse("2026-11-02T08:58:01+11:00")));
    }

    @Test
    public void spokenTextUsesAbsoluteMinutesAfterStart() throws Exception {
        assertEquals("Standup started 5 minutes ago.",
                standup().getSpokenText(OffsetDateTime.parse("2026-11-02T09:05:00+11:00")));
    }

    @Test
    public void spokenTextJoinsAllMatchingTemplatesInOrder() throws Exception {
        CalendarNotification notification = notification("{\"event\":{\"summary\":\"Standup\",\"start_time\":\"" + START_TIME + "\"},"
                + "\"play_datetime\":\"2026-11-02T08:50:00+11:00\",\"templated_sentences\":["
                + "{\"template\":\"Standup in {mins} minutes.\",\"min\":2,\"max\":null},"
                + "{\"template\":\"Standup is starting.\",\"min\":0,\"max\":2},"
                + "{\"template\":\"Ready?\",\"min\":null,\"max\":null},"
                + "{\"template\":\"Grab a coffee\",\"min\":null,\"max\":null}"
                + "]}");

        assertEquals("Standup in 10 minutes. Ready? Grab a coffee.",
                notification.getSpokenText(notification.getPlayDateTime()));
    }

    @Test
    public void spokenTextFallsBackWhenNoStartTime() throws Exception {
        CalendarNotification notification = notification("{\"event\":{\"summary\":\"Standup\"},"
                + "\"play_datetime\":\"2026-11-02T08:50:00+11:00\",\"templated_sentences\":" + TEMPLATED_SENTENCES + "}");

        assertEquals("It's time for Standup", notification.getSpokenText(notification.getPlayDateTime()));
    }

    @Test
    public void spokenTextFallsBackWhenNoTemplateMatches() throws Exception {
        CalendarNotification notification = notification("{\"event\":{\"summary\":\"Standup\",\"start_time\":\"" + START_TIME + "\"},"
                + "\"play_datetime\":\"2026-11-02T08:50:00+11:00\","
                + "\"templated_sentences\":[{\"template\":\"Soon\",\"min\":0,\"max\":5}]}");

        assertEquals("It's time for Standup", notification.getSpokenText(notification.getPlayDateTime()));
    }
}
