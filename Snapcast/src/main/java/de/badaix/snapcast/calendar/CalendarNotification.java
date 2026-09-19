package de.badaix.snapcast.calendar;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * A single entry returned by the "Calendar Alarms URL" endpoint, e.g.:
 * {"event":{"summary":"..."},"type":"announce","due_datetime":"...","play_datetime":"..."}
 */
public class CalendarNotification {
    private final String summary;
    private final String type;
    private final OffsetDateTime playDateTime;

    public CalendarNotification(JSONObject json) throws JSONException, DateTimeParseException {
        JSONObject event = json.getJSONObject("event");
        summary = event.getString("summary");
        type = json.optString("type", "");
        playDateTime = OffsetDateTime.parse(json.getString("play_datetime"));
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
}
