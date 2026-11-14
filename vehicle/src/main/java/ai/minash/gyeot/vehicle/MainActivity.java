package ai.minash.gyeot.vehicle;

import static ai.minash.gyeot.vehicle.CompanionClient.KIDS_PAUSE;
import static ai.minash.gyeot.vehicle.CompanionClient.KIDS_RESUME;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final String TAG = "GyeotVehicle";
    private static final String LOCK_SCREEN = "LOCK_SCREEN";
    private static final long SCREEN_LOCK_CONFIRMATION_MS = 6000;
    private static final int INK = GraphiteUi.INK;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService kidsNetwork = Executors.newSingleThreadExecutor();
    private Set<String> knownAlerts = Collections.emptySet();
    private CompanionClient client;
    private CarGate carGate;
    private PleosVoice voice;
    private boolean running;
    private boolean foreground;
    private CarGate.Mode carMode = CarGate.Mode.UNAVAILABLE;
    private boolean previewShared;
    private boolean sleepVoiceEnabled;
    private int previewGeneration;
    private int generation;
    private int commandGeneration;
    private boolean kidsCommandInFlight;
    private long screenLockBaseline;
    private long screenLockDeadlineMs;
    private int lifecycle;
    private String alertSource;
    private int listeningLifecycle = -1;
    private EditText host;
    private EditText token;
    private TextView status;
    private TextView feedback;
    private TextView sleep;
    private TextView duration;
    private TextView notices;
    private TextView vehicleState;
    private Button previewButton;
    private Switch sleepVoice;
    private ImageView preview;
    private LinearLayout pairing;
    private LinearLayout screen;
    private LinearLayout header;
    private LinearLayout body;
    private LinearLayout seatPanel;
    private LinearLayout statePanel;
    private LinearLayout footer;
    private LinearLayout controls;
    private LinearLayout parkedControls;
    private FrameLayout cameraFrame;
    private SeatMapView seatMap;
    private TextView title;
    private TextView seatLabel;
    private TextView observationSource;
    private TextView durationLabel;
    private TextView mediaStatus;
    private TextView previewPolicy;
    private View bodyDivider;
    private Button voiceButton;
    private Button pauseButton;
    private final List<Button> secondaryButtons = new ArrayList<>();
    private int nightMode;
    private Button settingsButton;
    private AlertDialog settingsDialog;
    private float uiScale = 1;
    private boolean compactLayout;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sleepVoiceEnabled = getPreferences(MODE_PRIVATE).getBoolean("sleep_voice", true);
        buildScreen();
        applyDayNightPalette();
        voice = new PleosVoice(this, this::recognized, message -> main.post(() -> feedback.setText(message)));
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if ((configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK) != nightMode) applyDayNightPalette();
    }

    private void applyDayNightPalette() {
        nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        int background = getColor(R.color.graphite_background);
        int footerColor = getColor(R.color.graphite_footer);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(footerColor);
        screen.setBackgroundColor(background);
        preview.setBackgroundColor(background);
        pairing.setBackgroundColor(background);
        footer.setBackgroundColor(footerColor);
        GraphiteUi.colorButton(voiceButton, true);
        for (Button button : secondaryButtons) GraphiteUi.colorButton(button, false);
        seatMap.invalidate();
        if (screen.isLaidOut()) sizeScreen(screen.getWidth(), screen.getHeight());
    }

    @Override protected void onStart() {
        super.onStart();
        running = true;
        int ticket = ++lifecycle;
        try { carGate = new CarGate(this, (mode, label) -> {
            if (running && ticket == lifecycle) setRestrictions(mode, label);
        }); }
        catch (LinkageError e) { setRestrictions(CarGate.Mode.UNAVAILABLE, "차량 서비스 미지원 · 화면 확인 잠김"); }
        main.post(poll);
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
    }

    @Override protected void onPause() {
        foreground = false;
        listeningLifecycle = -1;
        clearPreview();
        if (settingsDialog != null) settingsDialog.dismiss();
        if (voice != null) voice.stop();
        super.onPause();
    }

    @Override protected void onStop() {
        running = false;
        listeningLifecycle = -1;
        lifecycle++;
        generation++;
        screenLockDeadlineMs = 0;
        main.removeCallbacks(poll);
        clearObservation();
        setRestrictions(CarGate.Mode.UNAVAILABLE, "앱 대기 중");
        if (carGate != null) { carGate.close(); carGate = null; }
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (voice != null) voice.close();
        network.shutdownNow();
        kidsNetwork.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void buildScreen() {
        screen = column();
        header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        title = label("Alma", 36);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setLetterSpacing(-.04f);
        header.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        vehicleState = label("차량 상태 확인 중", 20);
        vehicleState.setId(R.id.vehicle_status);
        vehicleState.setSingleLine(true);
        header.addView(vehicleState, new LinearLayout.LayoutParams(0, -1, 1));
        status = label("휴대폰 연결 대기", 19);
        status.setId(R.id.connection_status);
        status.setTextColor(GraphiteUi.MUTED);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(status, new LinearLayout.LayoutParams(0, -1, 1.5f));
        settingsButton = button("연결 설정", this::showSettings);
        settingsButton.setId(R.id.connection_settings_action);
        settingsButton.setGravity(Gravity.CENTER);
        settingsButton.setVisibility(canConfigure() ? View.VISIBLE : View.GONE);
        header.addView(settingsButton);
        screen.addView(header);
        screen.addView(rule(), new LinearLayout.LayoutParams(-1, dp(1)));

        body = new LinearLayout(this);
        seatPanel = column();
        seatLabel = label("", 21);
        updateSeatLabel();
        seatPanel.addView(seatLabel);
        cameraFrame = new FrameLayout(this);
        seatMap = new SeatMapView(this);
        seatMap.setId(R.id.seat_map);
        seatMap.setSeat(getPreferences(MODE_PRIVATE).getInt("observation_seat", 1));
        cameraFrame.addView(seatMap, new FrameLayout.LayoutParams(-1, -1));
        preview = new ImageView(this);
        preview.setId(R.id.preview_image);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription("휴대폰 카메라 최근 화면");
        preview.setVisibility(View.GONE);
        cameraFrame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        seatPanel.addView(cameraFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(seatPanel, new LinearLayout.LayoutParams(0, -1, .42f));
        bodyDivider = rule();
        body.addView(bodyDivider, new LinearLayout.LayoutParams(dp(1), -1));
        statePanel = column();
        observationSource = label("휴대폰 연결을 기다리고 있어요", 21);
        observationSource.setTextColor(GraphiteUi.MUTED);
        statePanel.addView(observationSource);
        sleep = label("아이 상태\n확인 대기", 68);
        sleep.setId(R.id.observation_status);
        sleep.setMaxLines(3);
        sleep.setGravity(Gravity.TOP | Gravity.START);
        sleep.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statePanel.addView(sleep, new LinearLayout.LayoutParams(-1, 0, 1));
        notices = label("", 18);
        notices.setMaxLines(2);
        notices.setEllipsize(TextUtils.TruncateAt.END);
        notices.setVisibility(View.GONE);
        statePanel.addView(notices);
        statePanel.addView(rule(), new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout timer = new LinearLayout(this);
        timer.setGravity(Gravity.BOTTOM);
        durationLabel = label("관찰된 수면", 23);
        durationLabel.setTextColor(GraphiteUi.MUTED);
        timer.addView(durationLabel, new LinearLayout.LayoutParams(0, -2, 1));
        duration = label("--:--", 100);
        duration.setId(R.id.observed_sleep);
        duration.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        duration.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        duration.setSingleLine(true);
        timer.addView(duration);
        statePanel.addView(timer);
        mediaStatus = label("미디어 상태 확인 대기", 25);
        mediaStatus.setId(R.id.media_status);
        statePanel.addView(mediaStatus);
        body.addView(statePanel, new LinearLayout.LayoutParams(0, -1, .58f));
        screen.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        footer = column();
        controls = new LinearLayout(this);
        voiceButton = GraphiteUi.button(this, "음성으로 요청\n소리 줄여줘", true, this::listen);
        voiceButton.setId(R.id.voice_action);
        pauseButton = button("재생 멈춤", () -> send("PAUSE"));
        pauseButton.setId(R.id.pause_action);
        controls.addView(voiceButton, new LinearLayout.LayoutParams(0, dp(112), 1.65f));
        controls.addView(pauseButton, new LinearLayout.LayoutParams(0, dp(112), 1));
        footer.addView(controls);
        parkedControls = new LinearLayout(this);
        previewButton = button("아이 화면 확인", () -> showPreview(false));
        previewButton.setId(R.id.preview_action);
        previewButton.setGravity(Gravity.CENTER);
        previewButton.setVisibility(View.GONE);
        parkedControls.addView(previewButton, new LinearLayout.LayoutParams(-1, dp(56)));
        parkedControls.setVisibility(View.GONE);
        footer.addView(parkedControls);
        feedback = label("", 18);
        feedback.setTextColor(GraphiteUi.MUTED);
        feedback.setMaxLines(2);
        feedback.setEllipsize(TextUtils.TruncateAt.END);
        feedback.setGravity(Gravity.CENTER);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        footer.addView(feedback);
        previewPolicy = label("주차 후 아이 화면을 볼 수 있어요", 17);
        previewPolicy.setTextColor(GraphiteUi.MUTED);
        previewPolicy.setGravity(Gravity.CENTER);
        footer.addView(previewPolicy);
        screen.addView(footer);
        buildSettings();
        screen.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                sizeScreen(right - left, bottom - top);
            }
        });
        setContentView(screen);
    }

    private void buildSettings() {
        pairing = column();
        pairing.addView(label("출발 전에 휴대폰 앱을 열고 연결해 주세요", 16));
        host = new EditText(this); host.setTextColor(INK); host.setHintTextColor(Color.LTGRAY);
        host.setId(R.id.pairing_host);
        host.setHint("휴대폰 주소");
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        pairing.addView(host);
        token = new EditText(this); token.setTextColor(INK); token.setHintTextColor(Color.LTGRAY);
        token.setId(R.id.pairing_token);
        token.setHint("휴대폰에 표시된 연결 키");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setSaveEnabled(false);
        token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        pairing.addView(token);
        Button pairButton = button("휴대폰 연결", this::pair);
        pairButton.setId(R.id.pair_action);
        pairing.addView(pairButton, new LinearLayout.LayoutParams(-1, dp(64)));
        pairing.addView(label("설정한 관찰 위치", 16));
        RadioGroup seats = new RadioGroup(this);
        seats.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton left = new RadioButton(this); left.setText("2열 왼쪽"); left.setTextColor(INK);
        RadioButton right = new RadioButton(this); right.setText("2열 오른쪽"); right.setTextColor(INK);
        left.setTextSize(18); right.setTextSize(18);
        left.setId(View.generateViewId()); right.setId(View.generateViewId());
        seats.addView(left); seats.addView(right);
        int selected = getPreferences(MODE_PRIVATE).getInt("observation_seat", 1);
        seats.check(selected == 0 ? left.getId() : right.getId());
        seats.setOnCheckedChangeListener((group, id) -> {
            int seat = id == left.getId() ? 0 : 1;
            getPreferences(MODE_PRIVATE).edit().putInt("observation_seat", seat).apply();
            seatMap.setSeat(seat);
            updateSeatLabel();
        });
        pairing.addView(seats);
        sleepVoice = new Switch(this);
        sleepVoice.setText("잠듦과 기상 알림을 음성으로 받기");
        sleepVoice.setTextColor(INK);
        sleepVoice.setTextSize(18);
        sleepVoice.setMinHeight(dp(64));
        sleepVoice.setChecked(sleepVoiceEnabled);
        sleepVoice.setVisibility(View.GONE);
        sleepVoice.setOnCheckedChangeListener((view, enabled) -> {
            sleepVoiceEnabled = enabled;
            if (!enabled) voice.discardPendingRoutine();
            getPreferences(MODE_PRIVATE).edit().putBoolean("sleep_voice", enabled).apply();
        });
        pairing.addView(sleepVoice);
        LinearLayout mediaControls = new LinearLayout(this);
        mediaControls.addView(button("수면 모드", () -> send("QUIET")), new LinearLayout.LayoutParams(0, dp(64), 1));
        mediaControls.addView(button("이어서 재생", () -> send("RESUME")), new LinearLayout.LayoutParams(0, dp(64), 1));
        pairing.addView(mediaControls);
        pairing.addView(label("상태는 관찰에 따른 추정이에요. 알림이 없어도 보호자의 확인이 필요해요.", 14));
        pairing.setPadding(dp(24), dp(16), dp(24), dp(24));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(pairing);
        settingsDialog = new AlertDialog.Builder(this).setTitle("Alma 연결 설정")
                .setView(scroll).setPositiveButton("닫기", null).create();
    }

    private void showSettings() {
        if (canConfigure()) settingsDialog.show();
    }

    private boolean canConfigure() {
        return carMode == CarGate.Mode.PARKED
                || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    private void updateSeatLabel() {
        int seat = getPreferences(MODE_PRIVATE).getInt("observation_seat", 1);
        seatLabel.setText("관찰 위치 · 2열 " + (seat == 0 ? "왼쪽" : "오른쪽"));
    }

    private View rule() {
        View view = new View(this);
        view.setBackgroundColor(GraphiteUi.RULE);
        return view;
    }

    private int dp(float size) { return GraphiteUi.dp(this, size); }

    private int scaled(float size) { return dp(size * uiScale); }

    private float textSize(float desired, int minimumSp) {
        return Math.max(Math.max(desired, minimumSp),
                18f / getResources().getDisplayMetrics().scaledDensity);
    }

    private void sizeScreen(int width, int height) {
        float density = getResources().getDisplayMetrics().density;
        uiScale = Math.max(.4f, Math.min(1.4f, Math.min(width / density / 1440f, height / density / 1036f)));
        compactLayout = width < height || width / density < 760;
        header.setPadding(scaled(48), 0, scaled(48), 0);
        header.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(dp(64), scaled(94))));
        body.setPadding(scaled(48), scaled(38), scaled(48), scaled(38));
        body.setOrientation(compactLayout ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        seatPanel.setPadding(0, 0, compactLayout ? 0 : scaled(40), 0);
        statePanel.setPadding(compactLayout ? 0 : scaled(48), compactLayout ? scaled(20) : 0, 0, 0);
        seatPanel.setLayoutParams(compactLayout ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -1, .42f));
        statePanel.setLayoutParams(compactLayout ? new LinearLayout.LayoutParams(-1, 0, 1)
                : new LinearLayout.LayoutParams(0, -1, .58f));
        bodyDivider.setVisibility(compactLayout ? View.GONE : View.VISIBLE);
        sizeCameraFrame();
        footer.setPadding(scaled(48), scaled(26), scaled(48), scaled(20));
        title.setTextSize(textSize(36 * uiScale, 24));
        vehicleState.setTextSize(textSize(20 * uiScale, 14));
        status.setTextSize(textSize(19 * uiScale, 13));
        settingsButton.setTextSize(textSize(18 * uiScale, 13));
        settingsButton.setLayoutParams(new LinearLayout.LayoutParams(Math.max(dp(72), scaled(146)), Math.max(dp(48), scaled(54))));
        settingsButton.setPadding(scaled(10), 0, scaled(10), 0);
        seatLabel.setTextSize(textSize(21 * uiScale, 14));
        observationSource.setTextSize(textSize(21 * uiScale, 14));
        sleep.setPadding(0, scaled(compactLayout ? 12 : 28), 0, scaled(12));
        sleep.setAutoSizeTextTypeUniformWithConfiguration(20, Math.max(21, Math.round(68 * uiScale)), 1, TypedValue.COMPLEX_UNIT_SP);
        notices.setTextSize(textSize(18 * uiScale, 14));
        notices.setPadding(0, 0, 0, scaled(16));
        durationLabel.setTextSize(textSize(23 * uiScale, 14));
        duration.setTextSize(textSize(100 * uiScale, 36));
        duration.setPadding(scaled(12), scaled(28), 0, 0);
        mediaStatus.setTextSize(textSize(25 * uiScale, 16));
        mediaStatus.setPadding(0, scaled(30), 0, 0);
        int actionHeight = Math.max(dp(64), scaled(112));
        LinearLayout.LayoutParams voiceParams = new LinearLayout.LayoutParams(0, actionHeight, 1.65f);
        voiceParams.setMarginEnd(scaled(20));
        voiceButton.setLayoutParams(voiceParams);
        pauseButton.setLayoutParams(new LinearLayout.LayoutParams(0, actionHeight, 1));
        float voiceSize = textSize(30 * uiScale, 20);
        voiceButton.setTextSize(voiceSize);
        SpannableString voiceLabel = new SpannableString("음성으로 요청\n소리 줄여줘");
        float hintScale = Math.max(.6f, 18f / (voiceSize * getResources().getDisplayMetrics().scaledDensity));
        voiceLabel.setSpan(new RelativeSizeSpan(hintScale), 8, voiceLabel.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        voiceButton.setText(voiceLabel);
        pauseButton.setTextSize(textSize(29 * uiScale, 19));
        voiceButton.setPadding(scaled(32), scaled(8), scaled(32), scaled(8));
        pauseButton.setPadding(scaled(28), scaled(8), scaled(28), scaled(8));
        parkedControls.setPadding(0, scaled(12), 0, 0);
        previewButton.setTextSize(textSize(20 * uiScale, 16));
        previewButton.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(dp(48), scaled(56))));
        feedback.setTextSize(textSize(18 * uiScale, 14));
        feedback.setMinHeight(Math.max(dp(24), scaled(28)));
        feedback.setPadding(0, scaled(6), 0, 0);
        previewPolicy.setTextSize(textSize(17 * uiScale, 13));
        previewPolicy.setPadding(0, scaled(6), 0, 0);
        GraphiteUi.icon(status, GraphiteUi.Icon.PHONE, GraphiteUi.MUTED, Math.max(dp(16), scaled(20)));
        GraphiteUi.icon(observationSource, GraphiteUi.Icon.MOON, GraphiteUi.MUTED, Math.max(dp(18), scaled(25)));
        GraphiteUi.icon(mediaStatus, GraphiteUi.Icon.VOLUME, getColor(R.color.graphite_primary), Math.max(dp(22), scaled(30)));
        GraphiteUi.icon(voiceButton, GraphiteUi.Icon.MIC, GraphiteUi.PRIMARY_INK, Math.max(dp(24), scaled(34)));
        GraphiteUi.icon(pauseButton, GraphiteUi.Icon.PAUSE, GraphiteUi.INK, Math.max(dp(24), scaled(32)));
        GraphiteUi.icon(previewPolicy, GraphiteUi.Icon.LOCK, GraphiteUi.MUTED, Math.max(dp(14), scaled(18)));
    }

    private void sizeCameraFrame() {
        boolean show = !compactLayout || preview.getVisibility() == View.VISIBLE;
        cameraFrame.setVisibility(show ? View.VISIBLE : View.GONE);
        cameraFrame.setLayoutParams(compactLayout
                ? new LinearLayout.LayoutParams(-1, dp(160))
                : new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBaselineAligned(false);
        return layout;
    }

    private TextView label(String text, int size) {
        return GraphiteUi.label(this, text, size, INK);
    }

    private Button button(String text, Runnable action) {
        Button secondary = GraphiteUi.button(this, text, false, action);
        secondaryButtons.add(secondary);
        return secondary;
    }

    private void setRestrictions(CarGate.Mode mode, String label) {
        boolean parked = mode == CarGate.Mode.PARKED;
        if (mode != carMode) {
            debugEvent("vehicle_mode=" + mode);
            if (parked) previewButton.setEnabled(true);
            if (carMode == CarGate.Mode.PARKED) feedback.setText("");
            if (mode == CarGate.Mode.UNAVAILABLE) voice.cancelSpeech();
        }
        carMode = mode;
        vehicleState.setText(mode == CarGate.Mode.PARKED ? "P 주차 중"
                : mode == CarGate.Mode.DRIVE ? "D 주행 중" : "차량 상태 확인 중");
        vehicleState.setContentDescription(label);
        pairing.setVisibility(canConfigure() ? View.VISIBLE : View.GONE);
        settingsButton.setVisibility(canConfigure() ? View.VISIBLE : View.GONE);
        if (!canConfigure() && settingsDialog != null) settingsDialog.dismiss();
        previewButton.setVisibility(parked ? View.VISIBLE : View.GONE);
        parkedControls.setVisibility(parked ? View.VISIBLE : View.GONE);
        sleepVoice.setVisibility(parked ? View.VISIBLE : View.GONE);
        notices.setVisibility(parked ? View.VISIBLE : View.GONE);
        previewPolicy.setText(parked ? "요청할 때 최근 화면을 5초간 보여줘요" : "주차 후 아이 화면을 볼 수 있어요");
        if (!parked) clearPreview();
    }

    private void clearPreview() {
        previewGeneration++;
        boolean visible = preview.getVisibility() == View.VISIBLE;
        preview.setImageDrawable(null);
        preview.setVisibility(View.GONE);
        previewButton.setEnabled(carMode == CarGate.Mode.PARKED);
        seatMap.setVisibility(View.VISIBLE);
        sizeCameraFrame();
        if (visible) debugEvent("preview_hidden");
    }

    private void clearObservation() {
        voice.invalidateObservationSpeech();
        previewShared = false;
        clearPreview();
        alertSource = null;
        knownAlerts = Collections.emptySet();
        sleep.setText("아이 상태\n확인 불가");
        duration.setText("--:--");
        duration.setContentDescription("수면 기록 확인 불가");
        observationSource.setText("카메라 상태를 확인해 주세요");
        mediaStatus.setText("미디어 상태 확인 불가");
        seatMap.setObserving(false);
        notices.setText("");
    }

    private void pair() {
        try {
            client = new CompanionClient(host.getText().toString(), token.getText().toString());
            generation++;
            screenLockDeadlineMs = 0;
            voice.cancelSpeech();
            clearObservation();
            status.setText("휴대폰 연결 확인 중");
            settingsDialog.dismiss();
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
                            clearObservation();
                        }
                    });
                } finally { main.post(() -> { if (running && lifeTicket == lifecycle) main.postDelayed(this, 1000); }); }
            });
        }
    };

    private void render(JSONObject state) {
        if (!state.optBoolean("connected")) {
            status.setText("연결 끊김 · 휴대폰 앱을 확인해 주세요");
            clearObservation();
            return;
        }
        String source = state.optString("source");
        previewShared = allowsCameraFrame(state);
        if (!previewShared) clearPreview();
        String connected = "scenario".equals(source) ? "연결됨 · 시나리오 입력" : "휴대폰 연결됨";
        status.setText(voice.hasSpeechFailure() ? connected + " · 음성 안내 중지" : connected);
        String current = state.optString("sleepState", "UNKNOWN");
        String name = switch (current) {
            case "ASLEEP" -> "아이가\n잠든 것 같아요";
            case "FALLING_ASLEEP" -> "잠드는 중인지\n살펴보고 있어요";
            case "AWAKE" -> "아이가\n깨어 있는 것 같아요";
            default -> "아이 상태를\n확인할 수 없어요";
        };
        sleep.setText(name);
        long seconds = Math.max(0, state.optLong("totalSleepMs")) / 1000;
        String observed = String.format(Locale.KOREA, "%02d:%02d", seconds / 60, seconds % 60);
        duration.setText(observed);
        duration.setContentDescription("관찰된 수면 " + observed);
        boolean camera = "camera".equals(source) && state.optBoolean("cameraReady");
        seatMap.setObserving(camera && !"UNKNOWN".equals(current));
        observationSource.setText("scenario".equals(source) ? "시연 입력으로 관찰 중"
                : camera && !"UNKNOWN".equals(current) ? "카메라로 관찰 중" : "카메라 위치를 확인해 주세요");
        JSONObject media = state.optJSONObject("media");
        mediaStatus.setText(media == null ? "미디어 상태 확인 불가"
                : !media.optBoolean("available") ? "휴대폰에서 영상을 선택해 주세요"
                : !media.optBoolean("playing") ? "뒷좌석 영상이 멈춰 있어요"
                : media.optBoolean("quiet") ? "영상 소리를 낮췄어요" : "뒷좌석 영상 재생 중");
        renderAlerts(state.optJSONArray("alerts"), source, current);
        confirmScreenLock(state);
    }

    private void renderAlerts(JSONArray alerts, String source, String current) {
        if (!source.equals(alertSource)) voice.invalidateObservationSpeech();
        if ("UNKNOWN".equals(current)) voice.discardPendingRoutine();
        boolean announce = ("camera".equals(source) || "scenario".equals(source))
                && source.equals(alertSource) && canSpeak();
        Set<String> currentAlerts = new HashSet<>();
        StringBuilder text = new StringBuilder();
        if ("camera".equals(source) && "UNKNOWN".equals(current)) {
            text.append("카메라 위치와 연결을 확인해 주세요.\n");
        }
        boolean posture = false;
        boolean vomiting = false;
        String routine = "NONE";
        int count = alerts == null ? 0 : alerts.length();
        for (int i = 0; i < count; i++) {
            JSONObject alert = alerts.optJSONObject(i);
            if (alert == null) continue;
            String id = alert.optString("id");
            if (id.isEmpty() || !currentAlerts.add(id)) continue;
            String kind = alert.optString("kind");
            if (i >= count - 3 && switch (kind) {
                case "VISIBILITY" -> false;
                case "SLEEP" -> "ASLEEP".equals(current);
                case "WAKE" -> "AWAKE".equals(current);
                default -> true;
            }) text.append(alert.optString("message")).append('\n');
            if (announce && !knownAlerts.contains(id)) {
                switch (kind) {
                    case "POSTURE" -> posture = true;
                    case "VOMIT_SUSPECTED" -> vomiting = true;
                    case "SLEEP", "WAKE" -> routine = kind;
                }
            }
        }
        alertSource = source;
        knownAlerts = currentAlerts;
        notices.setText(text.length() == 0 ? "새로운 확인 알림이 없어요" : text.toString().trim());
        if (!sleepVoiceEnabled) routine = "NONE";
        String cue = switch (routine) {
            case "SLEEP" -> "아이가 잠든 것 같아요.";
            case "WAKE" -> "아이가 깨어난 것 같아요.";
            default -> "";
        };
        if (posture || vomiting || !cue.isEmpty()) {
            debugEvent("care_cue mode=" + carMode + " posture=" + posture + " vomiting=" + vomiting
                    + " routine=" + routine);
            String prefix = "scenario".equals(source) ? "시연입니다. " : "";
            if (posture || vomiting) {
                String reason = posture && vomiting ? "아이 자세가 바뀌었고 구토가 의심돼요."
                        : posture ? "아이 자세가 크게 바뀌었어요." : "아이의 구토가 의심돼요.";
                voice.speak(() -> prefix + reason + (carMode == CarGate.Mode.DRIVE
                        ? " 안전한 곳에 정차한 뒤 확인해 주세요." : " 주차한 상태에서 아이를 확인해 주세요."),
                        PleosVoice.Speech.CARE);
            }
            if (!cue.isEmpty()) voice.speak(() -> prefix + cue, PleosVoice.Speech.ROUTINE);
        }
    }

    private boolean canSpeak() {
        return foreground && carMode != CarGate.Mode.UNAVAILABLE;
    }

    private static boolean allowsCameraFrame(JSONObject state) {
        return state.optBoolean("connected") && "camera".equals(state.optString("source"))
                && state.optBoolean("cameraReady") && state.optBoolean("previewShared");
    }

    private void debugEvent(String event) {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Log.d(TAG, event + " elapsed_ms=" + SystemClock.elapsedRealtime());
        }
    }

    private void send(String action) {
        send(action, false);
    }

    private void send(String action, boolean speakResult) {
        boolean kidsPlayback = CompanionClient.isKidsPlaybackAction(action);
        if (kidsPlayback && kidsCommandInFlight) {
            feedback.setText("앞선 유튜브 키즈 요청이 끝나면 다시 말해 주세요");
            return;
        }
        feedback.setText("요청을 처리하고 있어요");
        screenLockDeadlineMs = 0;
        int request = ++commandGeneration;
        CompanionClient target = client;
        if (target == null) { feedback.setText("휴대폰을 먼저 연결해 주세요"); return; }
        int ticket = generation;
        if (kidsPlayback) kidsCommandInFlight = true;
        ExecutorService worker = kidsPlayback ? kidsNetwork : network;
        worker.execute(() -> {
            try {
                if (LOCK_SCREEN.equals(action)) {
                    long baseline = target.requestScreenLock();
                    main.post(() -> awaitScreenLock(ticket, request, baseline));
                } else {
                    target.command(action);
                    main.post(() -> reportCommand(ticket, request, action, speakResult, null));
                }
            } catch (CompanionClient.ScreenLockRejected rejected) {
                main.post(() -> reportCommand(ticket, request, action, speakResult, rejected.reason));
            } catch (CompanionClient.KidsPlaybackRejected rejected) {
                main.post(() -> reportCommand(ticket, request, action, speakResult, rejected.reason));
            } catch (IOException e) {
                main.post(() -> reportCommand(ticket, request, action, speakResult, "unconfirmed"));
            } finally {
                if (kidsPlayback) main.post(() -> kidsCommandInFlight = false);
            }
        });
    }

    private boolean isCurrentCommand(int ticket, int request) {
        return running && ticket == generation && request == commandGeneration;
    }

    private void awaitScreenLock(int ticket, int request, long baseline) {
        if (!isCurrentCommand(ticket, request)) return;
        screenLockBaseline = baseline;
        screenLockDeadlineMs = SystemClock.elapsedRealtime() + SCREEN_LOCK_CONFIRMATION_MS;
        feedback.setText("휴대폰 화면 잠금 확인 중");
        main.postDelayed(() -> {
            if (isCurrentCommand(ticket, request) && screenLockDeadlineMs != 0) {
                screenLockDeadlineMs = 0;
                reportCommand(ticket, request, LOCK_SCREEN, true, "lock_unconfirmed");
            }
        }, SCREEN_LOCK_CONFIRMATION_MS);
    }

    private void confirmScreenLock(JSONObject state) {
        if (screenLockDeadlineMs == 0 || SystemClock.elapsedRealtime() > screenLockDeadlineMs) return;
        long sequence = CompanionClient.cameraFrameSequence(state);
        if ("camera".equals(state.optString("source")) && Boolean.FALSE.equals(state.opt("screenInteractive"))
                && Boolean.FALSE.equals(state.opt("appVisible")) && Boolean.TRUE.equals(state.opt("backgroundObservation"))
                && Boolean.TRUE.equals(state.opt("cameraReady")) && sequence > screenLockBaseline) {
            screenLockDeadlineMs = 0;
            debugEvent("screen_lock_confirmed frame_sequence=" + sequence);
            reportCommand(generation, commandGeneration, LOCK_SCREEN, true, null);
        }
    }

    private void reportCommand(int ticket, int request, String action, boolean speakResult, String failure) {
        if (!isCurrentCommand(ticket, request)) return;
        String message = failure == null ? switch (action) {
            case "PAUSE" -> "뒷좌석 영상을 멈췄어요";
            case "RESUME" -> "뒷좌석 영상을 다시 재생했어요";
            case KIDS_PAUSE -> "유튜브 키즈 영상을 멈췄어요";
            case KIDS_RESUME -> "유튜브 키즈 영상을 이어서 재생했어요";
            case "QUIET" -> "뒷좌석 소리와 앱 화면 밝기를 낮췄어요";
            case "DIM" -> "뒷좌석 앱 화면 밝기를 낮췄어요";
            case LOCK_SCREEN -> "휴대폰 화면을 잠갔어요. 카메라 관찰을 계속하고 있어요.";
            default -> "휴대폰에서 요청을 처리했어요";
        } : switch (failure) {
            case "lock_permission_required" -> "휴대폰에서 차량 잠금 권한을 허용해 주세요";
            case "background_observation_required" -> "휴대폰 화면이 켜져 있고 화면 꺼짐 중 관찰이 준비됐는지 확인해 주세요";
            case "foreground_required" -> "휴대폰에서 알마를 열고 다시 요청해 주세요";
            case "lock_unconfirmed" -> "휴대폰에서 화면 잠금과 카메라 상태를 확인해 주세요";
            case "kids_observation_required" -> "휴대폰에서 알마 연결과 다른 앱을 쓰는 동안 관찰 설정을 확인해 주세요";
            case "kids_control_disabled" -> "휴대폰의 알마에서 유튜브 키즈 재생 제어를 켜 주세요";
            case "kids_access_required" -> "휴대폰에서 알마의 알림 접근을 허용해 주세요";
            case "kids_app_unavailable" -> "휴대폰에 유튜브 키즈가 설치되어 있는지 확인해 주세요";
            case "kids_session_unavailable" -> "휴대폰에서 유튜브 키즈 영상을 열고 다시 요청해 주세요";
            case "kids_session_ambiguous" -> "휴대폰에서 유튜브 키즈 영상을 하나만 열고 다시 요청해 주세요";
            case "kids_action_unsupported" -> "유튜브 키즈에서 이 재생 요청을 받을 수 없어요";
            case "kids_start_state_mismatch" -> "휴대폰에서 유튜브 키즈의 현재 재생 상태를 확인해 주세요";
            case "kids_command_in_progress" -> "앞선 유튜브 키즈 요청이 끝나면 다시 말해 주세요";
            default -> CompanionClient.isKidsPlaybackAction(action)
                    ? "유튜브 키즈 재생 상태를 확인하지 못했어요" : "휴대폰에서 요청 결과를 확인하지 못했어요";
        };
        debugEvent("media_command=" + action + " confirmed=" + (failure == null));
        feedback.setText(message);
        if (speakResult && canSpeak()) voice.speak(message);
    }

    private void listen() {
        feedback.setText("음성 인식을 준비하고 있어요");
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
                case "이어서재생해줘", "다시재생해줘", "영상틀어줘", "영상을틀어줘", "뒷자리영상틀어줘" ->
                        send("RESUME", true);
                case "유튜브키즈멈춰줘", "유튜브키즈일시정지해줘", "유튜브키즈영상멈춰줘" ->
                        send(KIDS_PAUSE, true);
                case "유튜브키즈이어서재생해줘", "유튜브키즈다시재생해줘", "유튜브키즈영상다시재생해줘" ->
                        send(KIDS_RESUME, true);
                case "화면어둡게해줘", "뒷자리화면어둡게해줘", "뒷좌석화면밝기낮춰줘" -> send("DIM", true);
                case "휴대폰화면잠가줘", "휴대폰화면잠궈줘", "휴대폰화면꺼줘", "뒷좌석화면꺼줘",
                        "뒷자리휴대폰화면꺼줘", "뒷좌석휴대폰화면잠가줘", "뒷좌석휴대폰화면잠궈줘" ->
                        send(LOCK_SCREEN, true);
                case "아이보여줘", "아이화면보여줘", "뒷자리비춰줘", "뒷자리화면보여줘",
                        "뒷좌석화면보여줘" -> showPreview(true);
                case "아이화면닫아줘", "미리보기닫아줘", "뒷자리화면닫아줘" -> dismissPreview();
                default -> feedback.setText("소리 줄여줘, 멈춰줘, 이어서 재생해줘로 요청해 주세요");
            }
        });
    }

    private void dismissPreview() {
        boolean wasVisible = preview.getVisibility() == View.VISIBLE;
        clearPreview();
        debugEvent("preview_voice_close was_visible=" + wasVisible);
        feedback.setText(wasVisible ? "아이 화면을 닫았어요" : "열린 아이 화면이 없어요");
    }

    private void showPreview(boolean voiceRequest) {
        if (carMode != CarGate.Mode.PARKED) {
            debugEvent(voiceRequest ? "preview_voice_blocked" : "preview_button_blocked");
            String message = "차량이 허용한 주차 상태에서 화면을 확인할 수 있어요";
            feedback.setText(message);
            if (voiceRequest && canSpeak()) voice.speak("주차한 뒤 아이 화면을 볼 수 있어요.");
            return;
        }
        CompanionClient target = client;
        if (target == null) { feedback.setText("휴대폰을 먼저 연결해 주세요"); return; }
        if (!previewShared) {
            feedback.setText("휴대폰에서 카메라 상태와 화면 공유를 확인해 주세요");
            return;
        }
        int ticket = generation;
        clearPreview();
        int previewTicket = previewGeneration;
        previewButton.setEnabled(false);
        feedback.setText("아이 화면을 가져오고 있어요");
        network.execute(() -> {
            try {
                byte[] jpeg = target.preview();
                if (!allowsCameraFrame(target.state())) throw new IOException("카메라 공유를 확인할 수 없어요");
                android.graphics.Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (bitmap == null) throw new IOException("카메라 화면을 열 수 없어요");
                main.post(() -> {
                    if (foreground && carMode == CarGate.Mode.PARKED && previewShared
                            && ticket == generation && previewTicket == previewGeneration) {
                        preview.setImageBitmap(bitmap); preview.setVisibility(View.VISIBLE);
                        previewButton.setEnabled(true);
                        seatMap.setVisibility(View.GONE);
                        sizeCameraFrame();
                        debugEvent(voiceRequest ? "preview_voice_shown" : "preview_button_shown");
                        feedback.setText("휴대폰 카메라 최근 화면 · 다시 누르면 갱신돼요");
                        main.postDelayed(() -> {
                            if (previewTicket != previewGeneration) return;
                            clearPreview();
                        }, 5000);
                    }
                });
            } catch (IOException | JSONException e) { main.post(() -> {
                if (!running || ticket != generation || previewTicket != previewGeneration) return;
                clearPreview();
                feedback.setText("휴대폰 카메라 화면을 가져오지 못했어요");
            }); }
        });
    }
}
