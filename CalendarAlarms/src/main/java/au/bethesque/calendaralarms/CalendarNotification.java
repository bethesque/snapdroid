package au.bethesque.calendaralarms;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * A single entry returned by the "Calendar Alarms URL" endpoint, e.g.:
 * {"event":{"summary":"...","start_time":"..."},"type":"announce","due_datetime":"...","play_datetime":"...",
 * "duration_seconds":60,"template_options":[{"template":"... in {mins} minutes.","min":2,"max":null}]}
 */
public class CalendarNotification {
    private static final int DEFAULT_DURATION_SECONDS = 300;
    private static final String MINS_PLACEHOLDER = "{mins}";

    private final String summary;
    private final OffsetDateTime startTime;
    private final String type;
    private final OffsetDateTime playDateTime;
    private final int durationSeconds;
    private final List<TemplateOption> templateOptions = new ArrayList<>();

    public CalendarNotification(JSONObject json) throws JSONException, DateTimeParseException {
        JSONObject event = json.getJSONObject("event");
        summary = event.getString("summary");
        String startTimeString = event.optString("start_time", "");
        startTime = startTimeString.isEmpty() ? null : OffsetDateTime.parse(startTimeString);
        type = json.optString("type", "");
        playDateTime = OffsetDateTime.parse(json.getString("play_datetime"));
        durationSeconds = json.optInt("duration_seconds", DEFAULT_DURATION_SECONDS);

        JSONArray options = json.optJSONArray("template_options");
        if (options != null) {
            for (int i = 0; i < options.length(); i++) {
                templateOptions.add(new TemplateOption(options.getJSONObject(i)));
            }
        }
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

    /**
     * Returns the text to speak when this notification plays at the given time, from the
     * template option whose [min, max) range contains the whole minutes until the event
     * starts, or "It's time for <summary>" if there's no start time or no matching option.
     */
    public String getSpokenText(OffsetDateTime playTime) {
        if (startTime != null) {
            // Long division truncates towards zero, matching the server's int()
            long minutesUntilStart = Duration.between(playTime, startTime).getSeconds() / 60;
            for (TemplateOption option : templateOptions) {
                if (option.matches(minutesUntilStart))
                    return option.template.replace(MINS_PLACEHOLDER, String.valueOf(Math.abs(minutesUntilStart)));
            }
        }
        return "It's time for " + summary;
    }

    private static class TemplateOption {
        private final String template;
        // Null means unbounded
        private final Long min;
        private final Long max;

        TemplateOption(JSONObject json) throws JSONException {
            template = json.getString("template");
            min = json.isNull("min") ? null : json.getLong("min");
            max = json.isNull("max") ? null : json.getLong("max");
        }

        boolean matches(long minutes) {
            return (min == null || minutes >= min) && (max == null || minutes < max);
        }
    }
}
