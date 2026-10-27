package au.bethesque.calendaralarms;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * A single entry returned by the "Calendar Alarms URL" endpoint, e.g.:
 * {"event":{"summary":"..."},"type":"announce","due_datetime":"...","play_datetime":"...","duration_seconds":60,"audio_file_url":"..."}
 */
public class CalendarNotification {
    private static final int DEFAULT_DURATION_SECONDS = 300;

    private final String summary;
    private final String type;
    private final OffsetDateTime playDateTime;
    private final int durationSeconds;
    private final String audioFileUrl;

    public CalendarNotification(JSONObject json) throws JSONException, DateTimeParseException {
        JSONObject event = json.getJSONObject("event");
        summary = event.getString("summary");
        type = json.optString("type", "");
        playDateTime = OffsetDateTime.parse(json.getString("play_datetime"));
        durationSeconds = json.optInt("duration_seconds", DEFAULT_DURATION_SECONDS);
        audioFileUrl = json.optString("audio_file_url", "");
    }

    public String getSummary() {
        return summary;
    }

    public String getType() {
        return type;
    }

    public OffsetDateTime getPlayDateTime() {
        return playDateTime;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public String getAudioFileUrl() {
        return audioFileUrl;
    }
}
