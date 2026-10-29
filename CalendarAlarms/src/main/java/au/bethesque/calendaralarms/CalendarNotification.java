package au.bethesque.calendaralarms;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A single entry returned by the "Calendar Alarms URL" endpoint, e.g.:
 * {"event":{"summary":"...","start_time":"..."},"type":"announce","due_datetime":"...","play_datetime":"...",
 * "duration_seconds":60,"templated_sentences":[{"template":"... in {mins} minutes.","min":2,"max":null}]}
 */
public class CalendarNotification {
    private static final int DEFAULT_DURATION_SECONDS = 300;
    private static final String MINS_PLACEHOLDER = "{mins}";
    private static final Pattern ENDS_WITH_PUNCTUATION = Pattern.compile("[.!?]$");

    private final String summary;
    private final OffsetDateTime startTime;
    private final String type;
    private final OffsetDateTime playDateTime;
    private final int durationSeconds;
    private final List<TemplatedSentence> templatedSentences = new ArrayList<>();

    public CalendarNotification(JSONObject json) throws JSONException, DateTimeParseException {
        JSONObject event = json.getJSONObject("event");
        summary = event.getString("summary");
        String startTimeString = event.optString("start_time", "");
        startTime = startTimeString.isEmpty() ? null : OffsetDateTime.parse(startTimeString);
        type = json.optString("type", "");
        playDateTime = OffsetDateTime.parse(json.getString("play_datetime"));
        durationSeconds = json.optInt("duration_seconds", DEFAULT_DURATION_SECONDS);

        JSONArray sentences = json.optJSONArray("templated_sentences");
        if (sentences != null) {
            for (int i = 0; i < sentences.length(); i++) {
                templatedSentences.add(new TemplatedSentence(sentences.getJSONObject(i)));
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
     * Returns the text to speak when this notification plays at the given time, made by joining
     * (in order, adding a full stop to any not ending in . ! or ?) every templated sentence whose [min, max) range contains the whole minutes until
     * the event starts, or "It's time for <summary>" if there's no start time or no matching sentence.
     */
    public String getSpokenText(OffsetDateTime playTime) {
        if (startTime != null) {
            // Long division truncates towards zero, matching the server's int()
            long minutesUntilStart = Duration.between(playTime, startTime).getSeconds() / 60;
            List<String> matching = new ArrayList<>();
            for (TemplatedSentence sentence : templatedSentences) {
                if (sentence.matches(minutesUntilStart)) {
                    String text = sentence.template.replace(MINS_PLACEHOLDER, String.valueOf(Math.abs(minutesUntilStart)));
                    matching.add(ENDS_WITH_PUNCTUATION.matcher(text).find() ? text : text + ".");
                }
            }
            if (!matching.isEmpty())
                return String.join(" ", matching);
        }
        return "It's time for " + summary;
    }

    private static class TemplatedSentence {
        private final String template;
        // Null means unbounded
        private final Long min;
        private final Long max;

        TemplatedSentence(JSONObject json) throws JSONException {
            template = json.getString("template");
            min = json.isNull("min") ? null : json.getLong("min");
            max = json.isNull("max") ? null : json.getLong("max");
        }

        boolean matches(long minutes) {
            return (min == null || minutes >= min) && (max == null || minutes < max);
        }
    }
}
