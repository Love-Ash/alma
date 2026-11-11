package ai.minash.gyeot.vehicle;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(231, 238, 237);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Set<String> announced = new HashSet<>();
    private CompanionClient client;
    private CarGate carGate;
    private PleosVoice voice;
    private boolean running;
    private boolean previewAllowed;
    private int previewGeneration;
    private int generation;
    private int lifecycle;
    private boolean alertBaseline;
    private int listeningLifecycle = -1;
    private EditText host;
    private EditText token;
    private TextView status;
    private TextView feedback;
    private TextView sleep;
    private TextView duration;
    private TextView notices;
    private TextView vehicleState;
    private ImageView preview;
    private LinearLayout pairing;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(15, 28, 29));
        buildScreen();
        voice = new PleosVoice(this, this::recognized, message -> main.post(() -> status.setText(message)));
    }

    @Override protected void onStart() {
        super.onStart();
        running = true;
        int ticket = ++lifecycle;
        try { carGate = new CarGate(this, (allowed, label) -> main.post(() -> {
            if (running && ticket == lifecycle) setRestrictions(allowed, label);
        })); }
        catch (LinkageError e) { setRestrictions(false, "차량 서비스 미지원 · 화면 확인 잠김"); }
        main.post(poll);
    }

    @Override protected void onStop() {
        running = false;
        listeningLifecycle = -1;
        lifecycle++;
        generation++;
        main.removeCallbacks(poll);
        setRestrictions(false, "앱 대기 중");
        if (carGate != null) { carGate.close(); carGate = null; }
        if (voice != null) voice.stop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (voice != null) voice.close();
        network.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = column();
        page.setPadding(32, 24, 32, 24);
        page.setBackgroundColor(Color.rgb(15, 28, 29));
        scroll.addView(page);
        TextView title = label("곁", 34);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        page.addView(title);
        page.addView(label("뒷좌석 아이의 변화를 함께 살펴요", 18));
        vehicleState = label("차량 상태 연결 중", 15); page.addView(vehicleState);
        pairing = column();
        pairing.addView(label("출발 전에 휴대폰 앱을 열고 연결해 주세요", 16));
        host = new EditText(this); host.setTextColor(INK); host.setHintTextColor(Color.LTGRAY);
        host.setHint("휴대폰 주소 · 예: 192.168.0.10");
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        pairing.addView(host);
        token = new EditText(this); token.setTextColor(INK); token.setHintTextColor(Color.LTGRAY);
        token.setHint("휴대폰에 표시된 연결 키");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pairing.addView(token);
        pairing.addView(button("휴대폰 연결", this::pair));
        page.addView(pairing);
        status = label("아직 연결하지 않았어요", 17); page.addView(status);
        feedback = label("", 15);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(feedback);
        sleep = label("아이 상태 확인 대기", 28); page.addView(sleep);
        duration = label("관찰된 수면 00:00", 20); page.addView(duration);
        LinearLayout controls = new LinearLayout(this);
        controls.addView(button("수면 모드", () -> send("QUIET")), new LinearLayout.LayoutParams(0, 64, 1));
        controls.addView(button("재생 멈춤", () -> send("PAUSE")), new LinearLayout.LayoutParams(0, 64, 1));
        controls.addView(button("이어서 재생", () -> send("RESUME")), new LinearLayout.LayoutParams(0, 64, 1));
        page.addView(controls);
        page.addView(button("음성으로 요청", this::listen));
        notices = label("확인이 필요한 변화가 있으면 여기에 알려드려요", 20); page.addView(notices);
        page.addView(button("주차 후 아이 화면 확인", this::showPreview));
        preview = new ImageView(this); preview.setAdjustViewBounds(true); preview.setVisibility(View.GONE);
        page.addView(preview, new LinearLayout.LayoutParams(-1, 300));
        page.addView(label("상태는 관찰에 따른 추정이에요. 알림이 없어도 보호자의 확인이 필요해요.", 14));
        setContentView(scroll);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout;
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size);
        view.setTextColor(INK); view.setPadding(0, 8, 0, 8); return view;
    }

    private Button button(String text, Runnable action) {
        Button view = new Button(this); view.setText(text); view.setMinHeight(64);
        view.setOnClickListener(v -> action.run()); return view;
    }

    private void setRestrictions(boolean allowed, String label) {
        if (previewAllowed != allowed) previewGeneration++;
        previewAllowed = allowed;
        vehicleState.setText(label);
        pairing.setVisibility(allowed || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
                ? View.VISIBLE : View.GONE);
        if (!allowed) { preview.setImageDrawable(null); preview.setVisibility(View.GONE); }
    }

    private void pair() {
        try {
            client = new CompanionClient(host.getText().toString(), token.getText().toString());
            generation++;
            announced.clear();
            alertBaseline = false;
            previewGeneration++;
            preview.setImageDrawable(null);
            preview.setVisibility(View.GONE);
            status.setText("휴대폰 연결 확인 중");
        } catch (IllegalArgumentException e) { status.setText(e.getMessage()); }
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!running) return;
            CompanionClient target = client;
            int ticket = generation;
            int lifeTicket = lifecycle;
            if (target == null) { main.postDelayed(this, 1000); return; }
            network.execute(() -> {
                try {
                    JSONObject state = target.state();
                    main.post(() -> { if (running && ticket == generation) render(state); });
                } catch (IOException | JSONException e) {
                    main.post(() -> {
                        if (running && ticket == generation) {
                            status.setText("연결 끊김 · 휴대폰 앱과 네트워크를 확인해 주세요");
                            alertBaseline = false;
                            sleep.setText("아이 상태 확인 불가");
                            preview.setImageDrawable(null); preview.setVisibility(View.GONE);
                        }
                    });
                } finally { main.post(() -> { if (running && lifeTicket == lifecycle) main.postDelayed(this, 1000); }); }
            });
        }
    };

    private void render(JSONObject state) {
        String source = state.optString("source");
        if ("scenario".equals(source) || !state.optBoolean("cameraVisible")) {
            preview.setImageDrawable(null);
            preview.setVisibility(View.GONE);
        }
        status.setText("scenario".equals(source) ? "연결됨 · 시나리오 입력" : "연결됨 · 휴대폰 카메라 관찰");
        String current = state.optString("sleepState", "UNKNOWN");
        String name = switch (current) {
            case "ASLEEP" -> "아이가 잠든 것 같아요";
            case "FALLING_ASLEEP" -> "잠드는 중인지 살펴보고 있어요";
            case "AWAKE" -> "아이가 깨어 있는 것 같아요";
            default -> "아이 상태를 확인할 수 없어요";
        };
        sleep.setText(name);
        long seconds = state.optLong("totalSleepMs") / 1000;
        duration.setText(String.format(Locale.KOREA, "관찰된 수면 %02d:%02d", seconds / 60, seconds % 60));
        JSONArray alerts = state.optJSONArray("alerts");
        StringBuilder text = new StringBuilder();
        if (alerts != null) for (int i = Math.max(0, alerts.length() - 3); i < alerts.length(); i++) {
            JSONObject alert = alerts.optJSONObject(i);
            if (alert == null) continue;
            String message = alert.optString("message");
            text.append(message).append('\n');
            String id = alert.optString("id");
            if (announced.add(id) && alertBaseline && ("POSTURE".equals(alert.optString("kind")) || "VOMIT_SUSPECTED".equals(alert.optString("kind")))) {
                voice.speak(message + " 안전하게 정차해 확인해 주세요.");
            }
        }
        alertBaseline = true;
        notices.setText(text.length() == 0 ? "새로운 확인 알림이 없어요" : text.toString().trim());
    }

    private void send(String action) {
        send(action, false);
    }

    private void send(String action, boolean speakResult) {
        CompanionClient target = client;
        if (target == null) { status.setText("휴대폰을 먼저 연결해 주세요"); return; }
        int ticket = generation;
        network.execute(() -> {
            try {
                target.command(action);
                main.post(() -> reportCommand(ticket, "휴대폰에서 요청을 처리했어요", speakResult));
            } catch (IOException e) {
                main.post(() -> reportCommand(ticket, "요청을 처리하지 못했어요", speakResult));
            }
        });
    }

    private void reportCommand(int ticket, String message, boolean speakResult) {
        if (!running || ticket != generation) return;
        feedback.setText(message);
        if (speakResult) voice.speak(message);
    }

    private void listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1); return;
        }
        listeningLifecycle = lifecycle;
        voice.listen();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 1 && running && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) listen();
    }

    private void recognized(String text) {
        main.post(() -> {
            if (!running || listeningLifecycle != lifecycle || text == null) return;
            listeningLifecycle = -1;
            String phrase = text.replaceAll("[\\s.!?。]+", "");
            switch (phrase) {
                case "멈춰줘", "영상멈춰줘", "재생멈춰줘", "뒷자리영상멈춰줘" -> send("PAUSE", true);
                case "소리줄여줘", "뒷자리소리줄여줘", "수면모드", "수면모드켜줘" -> send("QUIET", true);
                case "이어서재생해줘", "영상틀어줘", "뒷자리영상틀어줘" -> send("RESUME", true);
                case "아이보여줘", "뒷자리비춰줘", "뒷자리화면보여줘" -> showPreview();
                default -> status.setText("소리 줄여줘, 멈춰줘, 이어서 재생해줘로 요청해 주세요");
            }
        });
    }

    private void showPreview() {
        if (!previewAllowed) { feedback.setText("차량이 허용한 주차 상태에서 화면을 확인할 수 있어요"); return; }
        CompanionClient target = client;
        if (target == null) { status.setText("휴대폰을 먼저 연결해 주세요"); return; }
        int ticket = generation;
        int previewTicket = ++previewGeneration;
        network.execute(() -> {
            try {
                byte[] jpeg = target.preview();
                android.graphics.Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                main.post(() -> {
                    if (running && previewAllowed && ticket == generation && previewTicket == previewGeneration && bitmap != null) {
                        preview.setImageBitmap(bitmap); preview.setVisibility(View.VISIBLE);
                        feedback.setText("휴대폰 카메라 최근 화면 · 다시 누르면 갱신돼요");
                        main.postDelayed(() -> {
                            if (previewTicket != previewGeneration) return;
                            preview.setImageDrawable(null);
                            preview.setVisibility(View.GONE);
                        }, 5000);
                    }
                });
            } catch (IOException e) { main.post(() -> {
                if (!running || ticket != generation || previewTicket != previewGeneration) return;
                preview.setImageDrawable(null);
                preview.setVisibility(View.GONE);
                feedback.setText("휴대폰 카메라 화면을 가져오지 못했어요");
            }); }
        });
    }
}
