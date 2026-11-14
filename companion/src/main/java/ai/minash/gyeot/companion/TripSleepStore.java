package ai.minash.gyeot.companion;

import android.content.Context;
import android.content.SharedPreferences;

import ai.minash.gyeot.core.CareEngine.Interval;
import ai.minash.gyeot.core.CareEngine.Result;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class TripSleepStore {
    private static final String ACTIVE = "active";
    private static final String TRIP_PREFIX = "trip:";
    private final SharedPreferences preferences;

    TripSleepStore(Context context) {
        preferences = context.getSharedPreferences("trip_sleep", Context.MODE_PRIVATE);
    }

    record Span(long startMs, long endMs) {
        boolean overlaps(Span other) {
            return startMs < other.endMs && other.startMs < endMs;
        }
    }

    record Trip(String id, long startedAt, long durationMs, boolean interrupted,
                List<Span> sleep, List<Span> gaps) {
        long totalSleepMs() {
            long total = 0;
            for (Span span : sleep) total += span.endMs - span.startMs;
            return total;
        }

        Trip corrected(int index, Span replacement) {
            if (replacement.startMs < 0 || replacement.endMs <= replacement.startMs
                    || replacement.endMs > durationMs) return null;
            for (Span gap : gaps) if (replacement.overlaps(gap)) return null;
            List<Span> updated = new ArrayList<>(sleep);
            updated.remove(index);
            for (Span span : updated) if (replacement.overlaps(span)) return null;
            updated.add(replacement);
            updated.sort(Comparator.comparingLong(Span::startMs));
            return new Trip(id, startedAt, durationMs, interrupted, Collections.unmodifiableList(updated), gaps);
        }
    }

    record Recording(String id, long wallStart, long elapsedStart) {
        Recording(long wallStart, long elapsedStart) {
            this(UUID.randomUUID().toString(), wallStart, elapsedStart);
        }

        Trip snapshot(long now, Result result) {
            return new Trip(id, wallStart, now - elapsedStart, false,
                    offsets(result.sleepIntervals, result.currentSleepInterval, elapsedStart),
                    offsets(result.observationGaps, result.currentObservationGap, elapsedStart));
        }
    }

    private static List<Span> offsets(List<Interval> completed, Interval open, long start) {
        List<Span> spans = new ArrayList<>();
        for (Interval interval : completed) append(spans, interval, start);
        if (open != null) append(spans, open, start);
        return Collections.unmodifiableList(spans);
    }

    private static void append(List<Span> spans, Interval interval, long start) {
        if (interval.endMs > interval.startMs) {
            spans.add(new Span(interval.startMs - start, interval.endMs - start));
        }
    }

    boolean checkpoint(Trip trip) {
        return preferences.edit().putString(ACTIVE, encode(trip)).commit();
    }

    boolean finish(Trip trip) {
        return preferences.edit().putString(TRIP_PREFIX + trip.id, encode(trip)).remove(ACTIVE).commit();
    }

    boolean recoverInterrupted() {
        Trip saved = decode(preferences.getString(ACTIVE, null));
        return saved == null || finish(new Trip(saved.id, saved.startedAt, saved.durationMs, true,
                saved.sleep, saved.gaps));
    }

    boolean saveCorrection(Trip trip) {
        return preferences.edit().putString(TRIP_PREFIX + trip.id, encode(trip)).commit();
    }

    boolean delete(String id) {
        return preferences.edit().remove(TRIP_PREFIX + id).commit();
    }

    List<Trip> history() {
        List<Trip> trips = new ArrayList<>();
        preferences.getAll().forEach((key, value) -> {
            if (key.startsWith(TRIP_PREFIX) && value instanceof String json) {
                Trip trip = decode(json);
                if (trip != null) trips.add(trip);
            }
        });
        trips.sort(Comparator.comparingLong(Trip::startedAt).reversed());
        return trips;
    }

    private static String encode(Trip trip) {
        try {
            return new JSONObject().put("id", trip.id).put("startedAt", trip.startedAt)
                    .put("durationMs", trip.durationMs).put("interrupted", trip.interrupted)
                    .put("sleep", encodeSpans(trip.sleep)).put("gaps", encodeSpans(trip.gaps)).toString();
        } catch (JSONException invalidRecord) {
            throw new IllegalStateException("Unable to encode trip", invalidRecord);
        }
    }

    private static JSONArray encodeSpans(List<Span> spans) {
        JSONArray array = new JSONArray();
        for (Span span : spans) array.put(new JSONArray().put(span.startMs).put(span.endMs));
        return array;
    }

    private static Trip decode(String json) {
        if (json == null) return null;
        try {
            JSONObject record = new JSONObject(json);
            return new Trip(record.getString("id"), record.getLong("startedAt"),
                    record.getLong("durationMs"), record.optBoolean("interrupted"),
                    decodeSpans(record.getJSONArray("sleep")), decodeSpans(record.getJSONArray("gaps")));
        } catch (JSONException invalidRecord) {
            return null;
        }
    }

    private static List<Span> decodeSpans(JSONArray array) throws JSONException {
        List<Span> spans = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONArray span = array.getJSONArray(index);
            spans.add(new Span(span.getLong(0), span.getLong(1)));
        }
        return Collections.unmodifiableList(spans);
    }
}
