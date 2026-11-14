package ai.minash.gyeot.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import ai.minash.gyeot.companion.TripSleepStore.Span;
import ai.minash.gyeot.companion.TripSleepStore.Trip;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TripSleepHistory {
    private static final Pattern OFFSET = Pattern.compile("(\\d{1,6}):([0-5]\\d):([0-5]\\d)(?:\\.(\\d{3}))?");
    private final Activity activity;
    private final TripSleepStore store;

    TripSleepHistory(Activity activity, TripSleepStore store) {
        this.activity = activity;
        this.store = store;
    }

    void show() {
        List<Trip> history = store.history();
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle("이동별 수면 기록")
                .setNegativeButton("닫기", null);
        if (history.isEmpty()) {
            dialog.setMessage("저장된 이동 기록이 없습니다.");
        } else {
            String[] labels = history.stream().map(this::summary).toArray(String[]::new);
            dialog.setItems(labels, (parent, index) -> showTrip(history.get(index)));
        }
        dialog.show();
    }

    private String summary(Trip trip) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(trip.startedAt())) + "  /  " + "추정 수면 " + duration(trip.totalSleepMs());
    }

    private void showTrip(Trip trip) {
        LinearLayout body = column();
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("이동 기록")
                .setView(scroll).setNegativeButton("목록", (parent, which) -> show()).create();
        text(body, summary(trip));
        if (trip.interrupted()) text(body, "앱이 종료되기 전 마지막 저장 시점까지 기록했습니다.");
        text(body, "추정 수면 구간");
        if (trip.sleep().isEmpty()) text(body, "관찰된 수면 구간 없음");
        for (int index = 0; index < trip.sleep().size(); index++) {
            int position = index;
            Span span = trip.sleep().get(index);
            Button button = new Button(activity);
            button.setText("구간 정정  " + range(trip, span));
            button.setOnClickListener(view -> {
                dialog.dismiss();
                correct(trip, position);
            });
            body.addView(button);
        }
        text(body, "관찰할 수 없었던 구간");
        if (trip.gaps().isEmpty()) text(body, "기록된 관찰 공백 없음");
        for (Span gap : trip.gaps()) text(body, range(trip, gap));
        Button delete = new Button(activity);
        delete.setText("이동 기록 삭제");
        delete.setOnClickListener(view -> new AlertDialog.Builder(activity).setTitle("이 이동 기록을 삭제할까요?")
                .setNegativeButton("취소", null).setPositiveButton("삭제", (confirmation, which) -> {
                    if (!store.delete(trip.id())) {
                        saveError();
                        return;
                    }
                    dialog.dismiss();
                    show();
                }).show());
        body.addView(delete);
        dialog.show();
    }

    private void correct(Trip trip, int index) {
        Span original = trip.sleep().get(index);
        LinearLayout body = column();
        text(body, "이동 시작 후 경과 시간 (시:분:초)");
        EditText start = timeField(body, "수면 시작", original.startMs());
        EditText end = timeField(body, "수면 종료", original.endMs());
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("수면 구간 정정").setView(body)
                .setNegativeButton("취소", (parent, which) -> showTrip(trip))
                .setPositiveButton("저장", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            long from = parse(start.getText().toString());
            long to = parse(end.getText().toString());
            if (from < 0) { start.setError("시:분:초로 입력해 주세요."); return; }
            if (to < 0) { end.setError("시:분:초로 입력해 주세요."); return; }
            Trip corrected = trip.corrected(index, new Span(from, to));
            if (corrected == null) {
                end.setError("이동 시간 안에서 다른 수면 구간과 관찰 공백을 피해서 정정해 주세요.");
                return;
            }
            if (!store.saveCorrection(corrected)) { saveError(); return; }
            dialog.dismiss();
            showTrip(corrected);
        });
    }

    private LinearLayout column() {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        return body;
    }

    private EditText timeField(LinearLayout body, String name, long offset) {
        TextView label = text(body, name);
        EditText input = new EditText(activity);
        input.setId(View.generateViewId());
        label.setLabelFor(input.getId());
        input.setSingleLine(true);
        input.setText(String.format(Locale.ROOT, "%s.%03d", duration(offset), offset % 1000));
        body.addView(input);
        return input;
    }

    private TextView text(LinearLayout body, String value) {
        TextView label = new TextView(activity);
        label.setText(value);
        body.addView(label);
        return label;
    }

    private static long parse(String value) {
        Matcher match = OFFSET.matcher(value.trim());
        if (!match.matches()) return -1;
        long hours = Long.parseLong(match.group(1));
        long minutes = Long.parseLong(match.group(2));
        long seconds = Long.parseLong(match.group(3));
        return (hours * 3600 + minutes * 60 + seconds) * 1000
                + (match.group(4) == null ? 0 : Long.parseLong(match.group(4)));
    }

    private static String duration(long milliseconds) {
        long seconds = milliseconds / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    private static String range(Trip trip, Span span) {
        DateFormat clock = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM);
        return clock.format(new Date(trip.startedAt() + span.startMs())) + " ~ "
                + clock.format(new Date(trip.startedAt() + span.endMs()));
    }

    private void saveError() {
        Toast.makeText(activity, "기록을 저장할 수 없습니다. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show();
    }
}
