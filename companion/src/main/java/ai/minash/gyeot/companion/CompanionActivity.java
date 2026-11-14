package ai.minash.gyeot.companion;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import ai.minash.gyeot.core.CareEngine;
import ai.minash.gyeot.core.CareEngine.Alert;
import ai.minash.gyeot.core.CareEngine.Config;
import ai.minash.gyeot.core.CareEngine.Frame;
import ai.minash.gyeot.core.CareEngine.Result;
import ai.minash.gyeot.core.CareEngine.SleepState;
import ai.minash.gyeot.companion.TripSleepStore.Recording;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.GeneralSecurityException;
import java.security.ProviderException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class CompanionActivity extends ComponentActivity {
    private static final int CAMERA_REQUEST = 11;
    private static final int VIDEO_REQUEST = 12;
    private static final int NOTIFICATION_REQUEST = 13;
    private static final int SCREEN_LOCK_REQUEST = 14;
    private static final long TRIP_CHECKPOINT_MS = 15_000;
    private static final long LOCK_TRANSITION_GRACE_MS = 1_500;
    private static final float DIM_BRIGHTNESS = .18f;
    private static final float NORMAL_BRIGHTNESS = .85f;
    private static final float QUIET_VOLUME = .12f;
    private static final float CLOSED_EYE_THRESHOLD = .28f;
    private static final int BACKGROUND = Color.rgb(25, 29, 33);
    private static final int INK = Color.rgb(241, 242, 243);
    private static final int MUTED = Color.rgb(169, 176, 183);
    private static final int ACCENT = Color.rgb(196, 203, 209);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService pairingExecutor = Executors.newSingleThreadExecutor();
    private final CareEngine engine = new CareEngine(new Config(8000, 2000, 2500, 2000, CLOSED_EYE_THRESHOLD, 28f));
    private final CameraObservationGate cameraObservation = new CameraObservationGate();
    private final FaceDetector detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build());
    private PreviewView preview;
    private VideoView video;
    private TextView stateLabel;
    private TextView detailLabel;
    private TextView pairingLabel;
    private TextView careNoticeLabel;
    private TextView noticeLabel;
    private TextView mediaLabel;
    private Button tripButton;
    private TripSleepStore tripStore;
    private Recording recording;
    private long lastTripCheckpoint;
    private Switch previewShare;
    private Switch screenOffSwitch;
    private Switch otherAppObservationSwitch;
    private Switch kidsControl;
    private KidsPlaybackController kidsPlaybackController;
    private Button screenLockButton;
    private DevicePolicyManager screenLockPolicy;
    private ComponentName screenLockAdmin;
    private boolean screenLockRemovalPending;
    private LinearLayout scenarios;
    private ProcessCameraProvider cameraProvider;
    private Preview cameraPreview;
    private ImageAnalysis analysis;
    private ServiceConnection observationConnection;
    private ObservationService observationService;
    private boolean updatingObservationSwitches;
    private volatile boolean backgroundObservation;
    private long unlockedBackgroundSince = -1;
    private PeerServer peer;
    private MediaPlayer media;
    private Uri videoUri;
    private volatile boolean active;
    private volatile boolean scenario;
    private boolean scenarioVisible = true;
    private boolean scenarioClosed;
    private boolean scenarioPosture;
    private boolean cameraVisible;
    private boolean cameraReady;
    private boolean quiet;
    private boolean autoQuiet = true;
    private float brightness = NORMAL_BRIGHTNESS;
    private float volume = 1f;
    private ValueAnimator quietTransition;
    private boolean quietRetryPending;
    private float brightnessBeforeTransition;
    private float volumeBeforeTransition;
    private long lastSleepFrameAt;
    private long lastCameraFrame;
    private long cameraFrameSequence;
    private long lastAnalyzed;
    private long lastPreviewAt;
    private volatile int cameraGeneration;
    private String token;
    private volatile String cachedState = "{\"connected\":false}";
    private volatile PreviewFrame sharedPreview;
    private volatile boolean sharePreview;
    private int previewGeneration;

    private final BroadcastReceiver screenStateChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (backgroundObservation && !active) enforceBackgroundObservation(SystemClock.elapsedRealtime());
        }
    };

    private static final class PreviewFrame {
        final byte[] jpeg;
        final long capturedAt;

        PreviewFrame(byte[] jpeg, long capturedAt) {
            this.jpeg = jpeg;
            this.capturedAt = capturedAt;
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!observationOpen()) return;
            long now = SystemClock.elapsedRealtime();
            if (backgroundObservation) {
                enforceBackgroundObservation(now);
                if (!observationOpen()) return;
            }
            if (scenario) {
                accept(new Frame(now, scenarioVisible, scenarioClosed ? .05f : .95f,
                        scenarioClosed ? .05f : .95f, scenarioPosture ? -45 : 0, 0, 0));
            } else if (now - lastCameraFrame > 2000) {
                cameraVisible = false;
                cameraReady = false;
                accept(new Frame(now, false, -1, -1, 0, 0));
            }
            render(now);
            if (active && sharePreview && now - lastPreviewAt > 1500
                    && preview.getPreviewStreamState().getValue() == PreviewView.StreamState.STREAMING) {
                capturePreview(now);
            }
            main.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        PackageManager packages = getPackageManager();
        if (packages.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)
                && !packages.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            screenLockPolicy = getSystemService(DevicePolicyManager.class);
            screenLockAdmin = new ComponentName(this, ScreenLockAdmin.class);
        }
        tripStore = new TripSleepStore(this);
        kidsPlaybackController = new KidsPlaybackController(this, main);
        buildScreen();
        IntentFilter screenActions = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        screenActions.addAction(Intent.ACTION_SCREEN_ON);
        ContextCompat.registerReceiver(this, screenStateChanges, screenActions, ContextCompat.RECEIVER_NOT_EXPORTED);
        if (!tripStore.recoverInterrupted()) notice("이전 이동 기록을 복구할 수 없습니다.");
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        unlockedBackgroundSince = -1;
        if (token == null) startPeer();
        if (backgroundObservation) {
            observationService.setScreenOffObservation(false);
            if (cameraPreview != null) cameraPreview.setSurfaceProvider(preview.getSurfaceProvider());
        } else {
            lastCameraFrame = SystemClock.elapsedRealtime();
            if (!scenario) startCamera();
        }
        if (videoUri != null) video.setVideoURI(videoUri);
        main.removeCallbacks(tick);
        main.post(tick);
    }

    @Override protected void onPause() {
        active = false;
        abortQuietTransition();
        previewShare.setChecked(false);
        video.stopPlayback();
        media = null;
        if (backgroundObservation) {
            long now = SystemClock.elapsedRealtime();
            if (cameraPreview != null) cameraPreview.setSurfaceProvider(null);
            enforceBackgroundObservation(now);
            if (backgroundObservation) render(now);
        } else {
            if (observationConnection != null) stopBackgroundObservation();
            closeObservation();
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        active = false;
        unregisterReceiver(screenStateChanges);
        stopBackgroundObservation();
        if (token != null) closeObservation();
        detector.close();
        cameraExecutor.shutdown();
        pairingExecutor.shutdownNow();
        video.stopPlayback();
        super.onDestroy();
    }

    private boolean observationOpen() { return active || backgroundObservation; }

    private boolean cameraPermissionGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean screenLockedOrOff() {
        return !getSystemService(PowerManager.class).isInteractive()
                || getSystemService(KeyguardManager.class).isKeyguardLocked();
    }

    private void enforceBackgroundObservation(long now) {
        if (!backgroundObservationAllowed(now)) {
            stopBackgroundObservation();
            return;
        }
        boolean needsWakeLock = !active && screenOffSwitch.isChecked()
                && (!otherAppObservationSwitch.isChecked() || screenLockedOrOff());
        observationService.setScreenOffObservation(needsWakeLock);
    }

    private boolean backgroundObservationAllowed(long now) {
        if (!cameraPermissionGranted() || !ObservationService.canShowNotification(this)) return false;
        if (active) {
            unlockedBackgroundSince = -1;
            return true;
        }
        if (screenLockedOrOff()) {
            unlockedBackgroundSince = -1;
            return screenOffSwitch.isChecked();
        }
        if (otherAppObservationSwitch.isChecked()) return true;
        if (unlockedBackgroundSince < 0) unlockedBackgroundSince = now;
        return screenOffSwitch.isChecked() && now - unlockedBackgroundSince < LOCK_TRANSITION_GRACE_MS;
    }

    private void closeObservation() {
        token = null;
        kidsControl.setChecked(false);
        pairingLabel.setText("");
        main.removeCallbacks(tick);
        stopCamera();
        if (peer != null) { peer.stop(); peer = null; }
        long now = SystemClock.elapsedRealtime();
        if (scenario) engine.markObservationUnavailable(now);
        if (recording != null) checkpointTrip(now, engine.snapshot(now));
        cachedState = "{\"connected\":false}";
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(44), dp(22), dp(30));
        scroll.addView(body);
        body.addView(label("Alma  /  뒷좌석 연결", 26, INK));
        body.addView(label("아이를 향해 기기를 고정하고 이 화면을 켜두세요.", 15, MUTED));
        stateLabel = label("확인 준비 중", 29, ACCENT);
        body.addView(stateLabel);
        detailLabel = label("", 14, MUTED);
        body.addView(detailLabel);
        preview = new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setContentDescription("전면 카메라 미리보기");
        body.addView(preview, new LinearLayout.LayoutParams(-1, dp(190)));
        body.addView(button("카메라 기준 자세 다시 맞추기", () -> {
            cameraObservation.invalidate();
            engine.resetPostureReference();
            notice("다음에 확인되는 얼굴을 기준 자세로 사용합니다.");
        }));
        Switch automatic = new Switch(this);
        automatic.setText("잠듦 추정 시 소리와 화면 자동으로 줄이기");
        automatic.setTextColor(INK);
        automatic.setTextSize(14);
        automatic.setChecked(true);
        automatic.setPadding(0, dp(8), 0, dp(8));
        automatic.setOnCheckedChangeListener((view, enabled) -> {
            autoQuiet = enabled;
            if (!enabled) abortQuietTransition();
        });
        body.addView(automatic);
        body.addView(label("이 기기에서 재생할 영상", 18, INK));
        body.addView(button("기기의 영상 선택", this::chooseVideo));
        video = new VideoView(this);
        video.setContentDescription("선택한 로컬 영상 플레이어");
        body.addView(video, new LinearLayout.LayoutParams(-1, dp(190)));
        video.setOnPreparedListener(player -> {
            media = player;
            player.setLooping(true);
            player.setVolume(volume, volume);
            mediaLabel.setText("영상 준비됨. 재생 버튼을 눌러 시작하세요.");
        });
        video.setOnErrorListener((player, what, extra) -> {
            media = null;
            mediaLabel.setText("이 영상을 재생할 수 없습니다. 다른 파일을 선택해 주세요.");
            return true;
        });
        LinearLayout controls = row();
        controls.addView(button("소리 줄이기", () -> applyCommand("QUIET")), weighted());
        controls.addView(button("멈추기", () -> applyCommand("PAUSE")), weighted());
        controls.addView(button("재생", () -> applyCommand("RESUME")), weighted());
        body.addView(controls);
        mediaLabel = label("연결 앱에서 선택한 영상만 제어합니다.", 14, MUTED);
        body.addView(mediaLabel);
        body.addView(label("차량 앱 연결", 18, INK));
        pairingLabel = label("", 14, MUTED);
        pairingLabel.setTextIsSelectable(true);
        body.addView(pairingLabel);
        body.addView(label("같은 로컬 네트워크에서 주소와 연결 키를 입력하세요. 기본 설정에서는 Alma를 벗어날 때 연결이 종료됩니다.", 13, MUTED));
        previewShare = new Switch(this);
        previewShare.setText("차량에 카메라 미리보기 공유");
        previewShare.setTextColor(INK);
        previewShare.setTextSize(14);
        previewShare.setMinHeight(dp(48));
        previewShare.setOnCheckedChangeListener((view, enabled) -> {
            sharePreview = enabled;
            previewGeneration++;
            sharedPreview = null;
            cachedState = stateJson(engine.snapshot(SystemClock.elapsedRealtime()));
        });
        body.addView(previewShare);
        screenOffSwitch = observationSwitch(R.string.screen_off_observation);
        body.addView(screenOffSwitch);
        body.addView(label(getString(R.string.screen_off_observation_help), 13, MUTED));
        otherAppObservationSwitch = observationSwitch(R.string.other_app_observation);
        body.addView(otherAppObservationSwitch);
        body.addView(label(getString(R.string.other_app_observation_help), 13, MUTED));
        if (screenLockPolicy != null) {
            screenLockButton = button(getString(R.string.screen_lock_allow), this::changeScreenLockPermission);
            body.addView(screenLockButton);
            updateScreenLockButton();
        }
        kidsControl = new Switch(this);
        kidsControl.setText(R.string.kids_control);
        kidsControl.setTextColor(INK);
        kidsControl.setTextSize(14);
        kidsControl.setMinHeight(dp(48));
        kidsControl.setOnCheckedChangeListener((view, enabled) -> {
            if (!enabled) {
                kidsPlaybackController.cancel();
            } else if (!kidsPlaybackController.accessGranted()) {
                view.setChecked(false);
                notice(getString(R.string.kids_access_settings_required));
            } else if (!kidsPlaybackController.targetInstalled()) {
                notice(getString(R.string.kids_app_required));
            }
        });
        body.addView(kidsControl);
        body.addView(label(getString(R.string.kids_control_help), 13, MUTED));
        body.addView(button(getString(R.string.kids_access_settings), () -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                notice(getString(R.string.kids_access_settings_unavailable));
            }
        }));
        tripButton = button("이동 기록 시작", this::toggleTrip);
        body.addView(tripButton);
        body.addView(button("이동별 수면 기록", () -> new TripSleepHistory(this, tripStore).show()));
        Switch scenarioSwitch = new Switch(this);
        scenarioSwitch.setText("시연 입력 사용");
        scenarioSwitch.setTextColor(INK);
        scenarioSwitch.setTextSize(16);
        scenarioSwitch.setPadding(0, dp(20), 0, dp(10));
        scenarioSwitch.setOnCheckedChangeListener((view, enabled) -> {
            if (enabled && !finishTrip()) {
                view.setChecked(false);
                return;
            }
            setScenario(enabled);
        });
        body.addView(scenarioSwitch);
        scenarios = new LinearLayout(this);
        scenarios.setOrientation(LinearLayout.VERTICAL);
        scenarios.addView(label("시연 입력입니다. 카메라의 실제 관찰 결과가 아닙니다.", 14, ACCENT));
        LinearLayout sleepRow = row();
        sleepRow.addView(button("눈 감김", () -> setScenarioFrame(true, true, false)), weighted());
        sleepRow.addView(button("눈 뜸", () -> setScenarioFrame(true, false, false)), weighted());
        scenarios.addView(sleepRow);
        LinearLayout alertRow = row();
        alertRow.addView(button("자세 변화", () -> setScenarioFrame(true, false, true)), weighted());
        alertRow.addView(button("얼굴 가림", () -> setScenarioFrame(false, false, false)), weighted());
        scenarios.addView(alertRow);
        scenarios.addView(button("구토 의심 사건 넣기", () -> {
            engine.markVomitingSuspected(SystemClock.elapsedRealtime());
            notice("구토 의심 시연 사건을 입력했습니다. 영상 분류 결과가 아닙니다.");
            render(SystemClock.elapsedRealtime());
        }));
        scenarios.setVisibility(View.GONE);
        body.addView(scenarios);
        careNoticeLabel = label("", 14, MUTED);
        careNoticeLabel.setVisibility(View.GONE);
        body.addView(careNoticeLabel);
        noticeLabel = label("얼굴과 눈 정보를 이용한 추정입니다. 구토와 호흡 상태를 자동 판정하지 않습니다.", 14, MUTED);
        body.addView(noticeLabel);
        setContentView(scroll);
    }

    private Switch observationSwitch(int labelId) {
        Switch toggle = new Switch(this);
        toggle.setText(labelId);
        toggle.setTextColor(INK);
        toggle.setTextSize(14);
        toggle.setMinHeight(dp(48));
        toggle.setOnCheckedChangeListener((view, enabled) -> {
            if (updatingObservationSwitches) return;
            if (!screenOffSwitch.isChecked() && !otherAppObservationSwitch.isChecked()) {
                stopBackgroundObservation();
            } else if (observationConnection == null) {
                startBackgroundObservation();
            } else if (backgroundObservation) {
                updateObservationWindow();
            }
        });
        return toggle;
    }

    private void startBackgroundObservation() {
        if (!active || scenario || !cameraPermissionGranted()) {
            clearObservationSwitches();
            notice("카메라 권한을 허용한 뒤 다시 켜 주세요.");
            return;
        }
        if (!ObservationService.canShowNotification(this)) {
            clearObservationSwitches();
            notice(getString(R.string.observation_notification_required));
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
            }
            return;
        }
        Intent intent = new Intent(this, ObservationService.class).setAction(ObservationService.ACTION_START);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (observationConnection != this || !active) return;
                observationService = ((ObservationService.LocalBinder) binder).service();
                observationService.setObserver(ready -> {
                    if (observationConnection != this) return;
                    if (!ready || !active || scenario) {
                        boolean wasObserving = backgroundObservation;
                        stopBackgroundObservation();
                        if (!wasObserving) notice(getString(R.string.observation_start_failed));
                        return;
                    }
                    backgroundObservation = true;
                    updateObservationWindow();
                    stopCamera();
                    startCamera();
                });
                try {
                    ContextCompat.startForegroundService(CompanionActivity.this, intent);
                } catch (IllegalStateException | SecurityException denied) {
                    stopBackgroundObservation();
                    notice(getString(R.string.observation_start_failed));
                }
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                if (observationConnection == this) stopBackgroundObservation();
            }

            @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }

            @Override public void onNullBinding(ComponentName name) { onServiceDisconnected(name); }
        };
        if (bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            observationConnection = connection;
        } else {
            clearObservationSwitches();
            notice(getString(R.string.observation_start_failed));
        }
    }

    private void clearObservationSwitches() {
        updatingObservationSwitches = true;
        screenOffSwitch.setChecked(false);
        otherAppObservationSwitch.setChecked(false);
        updatingObservationSwitches = false;
    }

    private void updateObservationWindow() {
        if (backgroundObservation && screenOffSwitch.isChecked()) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private boolean screenLockAdminActive() {
        return screenLockPolicy != null && screenLockPolicy.isAdminActive(screenLockAdmin);
    }

    private boolean screenLockPermissionGranted() {
        return !screenLockRemovalPending && screenLockAdminActive();
    }

    private boolean screenLockObservationReady() {
        return active && backgroundObservation && screenOffSwitch.isChecked() && !scenario && cameraReady
                && SystemClock.elapsedRealtime() - lastCameraFrame <= 2000
                && observationService != null && observationService.isObserving()
                && cameraPermissionGranted() && ObservationService.canShowNotification(this);
    }

    private void changeScreenLockPermission() {
        if (!active || screenLockRemovalPending) return;
        try {
            if (screenLockAdminActive()) {
                screenLockRemovalPending = true;
                screenLockPolicy.removeActiveAdmin(screenLockAdmin);
                updateScreenLockButton();
            } else {
                Intent consent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, screenLockAdmin)
                        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                getString(R.string.screen_lock_admin_explanation));
                startActivityForResult(consent, SCREEN_LOCK_REQUEST);
            }
        } catch (ActivityNotFoundException | SecurityException | IllegalStateException unavailable) {
            screenLockRemovalPending = false;
            updateScreenLockButton();
            notice(getString(R.string.screen_lock_settings_error));
        }
    }

    private void updateScreenLockButton() {
        if (screenLockButton == null) return;
        boolean granted = screenLockAdminActive();
        if (!granted) screenLockRemovalPending = false;
        int label = screenLockRemovalPending ? R.string.screen_lock_removing
                : granted ? R.string.screen_lock_remove : R.string.screen_lock_allow;
        String text = getString(label);
        if (!text.contentEquals(screenLockButton.getText())) screenLockButton.setText(text);
        screenLockButton.setEnabled(!screenLockRemovalPending);
    }

    private void stopBackgroundObservation() {
        boolean wasObserving = backgroundObservation;
        backgroundObservation = false;
        unlockedBackgroundSince = -1;
        clearObservationSwitches();
        updateObservationWindow();
        ObservationService service = observationService;
        observationService = null;
        ServiceConnection connection = observationConnection;
        observationConnection = null;
        if (service != null) {
            service.setObserver(null);
            if (service.isObserving()) service.stopObservation();
        }
        if (connection != null) unbindService(connection);
        if (wasObserving) {
            if (active) {
                stopCamera();
                if (!scenario) startCamera();
            } else {
                closeObservation();
            }
        }
    }

    private void setScenario(boolean enabled) {
        if (scenario == enabled) return;
        abortQuietTransition();
        scenario = enabled;
        if (enabled) stopBackgroundObservation();
        screenOffSwitch.setEnabled(!enabled);
        otherAppObservationSwitch.setEnabled(!enabled);
        if (enabled) kidsControl.setChecked(false);
        kidsControl.setEnabled(!enabled);
        tripButton.setEnabled(!enabled);
        scenarioVisible = true;
        scenarioClosed = false;
        scenarioPosture = false;
        cameraVisible = false;
        cameraReady = false;
        previewShare.setChecked(false);
        previewShare.setEnabled(!enabled);
        cameraObservation.invalidate();
        engine.reset();
        stopCamera();
        scenarios.setVisibility(enabled ? View.VISIBLE : View.GONE);
        preview.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled) accept(new Frame(SystemClock.elapsedRealtime(), true, .95f, .95f, 0, 0));
        if (!enabled && active) startCamera();
        notice(enabled ? "시연 입력을 사용합니다." : "전면 카메라에서 관찰합니다.");
    }

    private void toggleTrip() {
        if (recording != null) {
            finishTrip();
            return;
        }
        if (scenario || !active) return;
        long now = SystemClock.elapsedRealtime();
        abortQuietTransition();
        cameraObservation.invalidate();
        engine.beginTrip(now);
        recording = new Recording(System.currentTimeMillis(), now);
        tripButton.setText("이동 기록 종료");
        checkpointTrip(now, engine.snapshot(now));
    }

    private void checkpointTrip(long now, Result result) {
        lastTripCheckpoint = now;
        if (!tripStore.checkpoint(recording.snapshot(now, result))) {
            notice("이동 기록을 저장하지 못했습니다.");
        }
    }

    private boolean finishTrip() {
        if (recording == null) return true;
        long now = SystemClock.elapsedRealtime();
        if (!tripStore.finish(recording.snapshot(now, engine.snapshot(now)))) {
            notice("이동 기록을 저장하지 못했습니다. 종료 버튼을 다시 눌러 주세요.");
            return false;
        }
        recording = null;
        tripButton.setText("이동 기록 시작");
        notice("이동 기록을 저장했습니다.");
        return true;
    }

    private void setScenarioFrame(boolean visible, boolean closed, boolean posture) {
        scenarioVisible = visible;
        scenarioClosed = closed;
        scenarioPosture = posture;
        notice("시연 입력을 바꿨습니다. 상태가 지속되면 알림에 반영됩니다.");
    }

    private void startCamera() {
        if (!observationOpen() || scenario) return;
        if (!cameraPermissionGranted()) {
            if (active) requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
            return;
        }
        LifecycleOwner owner = backgroundObservation ? observationService : this;
        final int generation = ++cameraGeneration;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(getApplicationContext());
        future.addListener(() -> {
            if (!observationOpen() || scenario || generation != cameraGeneration) return;
            try {
                cameraProvider = future.get();
                unbindCameraUseCases();
                cameraPreview = new Preview.Builder().build();
                if (active) cameraPreview.setSurfaceProvider(preview.getSurfaceProvider());
                analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(cameraExecutor, image -> analyze(image, generation));
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, cameraPreview, analysis);
                notice("전면 카메라 관찰 중. 한 명의 얼굴이 보이도록 맞춰 주세요.");
            } catch (ExecutionException failedStart) {
                cameraStartFailed("카메라를 시작할 수 없습니다. 시연 입력으로 흐름을 확인할 수 있습니다.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cameraStartFailed("카메라 준비가 중단됐습니다.");
            } catch (IllegalArgumentException | IllegalStateException | SecurityException unavailable) {
                cameraStartFailed("전면 카메라를 사용할 수 없습니다. 권한과 기기 상태를 확인해 주세요.");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void cameraStartFailed(String message) {
        if (backgroundObservation) stopBackgroundObservation();
        notice(message);
    }

    private void stopCamera() {
        cameraGeneration++;
        abortQuietTransition();
        cameraReady = false;
        cameraVisible = false;
        if (!scenario) engine.markObservationUnavailable(SystemClock.elapsedRealtime());
        unbindCameraUseCases();
    }

    private void unbindCameraUseCases() {
        if (analysis != null) {
            analysis.clearAnalyzer();
            cameraProvider.unbind(analysis);
            analysis = null;
        }
        if (cameraPreview != null) {
            cameraProvider.unbind(cameraPreview);
            cameraPreview = null;
        }
    }

    @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
    private void analyze(ImageProxy image, int generation) {
        int observationEpoch = cameraObservation.epoch();
        long now = SystemClock.elapsedRealtime();
        if (!observationOpen() || scenario || generation != cameraGeneration || now - lastAnalyzed < 250) {
            image.close();
            return;
        }
        lastAnalyzed = now;
        if (image.getImage() == null) { image.close(); return; }
        InputImage input = InputImage.fromMediaImage(image.getImage(), image.getImageInfo().getRotationDegrees());
        detector.process(input)
                .addOnSuccessListener(ContextCompat.getMainExecutor(this), faces ->
                        onCameraResult(generation, observationEpoch, now, SystemClock.elapsedRealtime(), faces))
                .addOnFailureListener(ContextCompat.getMainExecutor(this), failure ->
                        onCameraResult(generation, observationEpoch, now, SystemClock.elapsedRealtime(), null))
                .addOnCompleteListener(task -> image.close());
    }

    private void onCameraResult(int generation, int observationEpoch, long arrivedAt, long completedAt,
            List<Face> faces) {
        if (!observationOpen() || scenario || generation != cameraGeneration
                || !cameraObservation.accepts(observationEpoch, arrivedAt, completedAt)) return;
        if (!active && !screenOffSwitch.isChecked() && screenLockedOrOff()) return;

        // Keep camera and media freshness tied to analyzer arrival. The engine clock also includes UI snapshots.
        lastCameraFrame = arrivedAt;
        cameraReady = faces != null;
        cameraVisible = cameraReady && faces.size() == 1;
        if (cameraReady) cameraFrameSequence++;
        if (!cameraVisible) {
            accept(new Frame(completedAt, false, -1, -1, 0, 0), arrivedAt);
            if (!cameraReady) notice("영상 분석이 중단됐습니다. 얼굴과 조명을 확인해 주세요.");
            return;
        }
        Face face = faces.get(0);
        float pitch = face.getHeadEulerAngleX();
        float roll = face.getHeadEulerAngleZ();
        float yaw = face.getHeadEulerAngleY();
        Float left = face.getLeftEyeOpenProbability();
        Float right = face.getRightEyeOpenProbability();
        boolean frontal = Math.abs(yaw) <= 18;
        accept(new Frame(completedAt, true, frontal && left != null ? left : -1,
                frontal && right != null ? right : -1, pitch, roll, yaw), arrivedAt);
    }

    private void accept(Frame frame) {
        accept(frame, frame.timestampMs);
    }

    private void accept(Frame frame, long arrivedAt) {
        Result result = engine.accept(frame);
        if (result.sleepState != SleepState.ASLEEP) {
            abortQuietTransition();
            return;
        }
        // ASLEEP can persist during wake debounce, when the latest frame shows open eyes.
        boolean bothClosed = frame.leftEyeOpen <= CLOSED_EYE_THRESHOLD
                && frame.rightEyeOpen <= CLOSED_EYE_THRESHOLD;
        if (bothClosed) lastSleepFrameAt = arrivedAt;
        boolean retry = quietRetryPending && bothClosed
                && CameraObservationGate.isFresh(arrivedAt, SystemClock.elapsedRealtime())
                && !screenLockedOrOff();
        if (active && autoQuiet && !quiet && (result.requestQuiet || retry)) startQuietTransition();
    }

    private void startQuietTransition() {
        stopQuietTransition();
        brightnessBeforeTransition = brightness;
        volumeBeforeTransition = volume;
        ValueAnimator transition = ValueAnimator.ofFloat(0f, 1f);
        transition.setDuration(2000);
        transition.addUpdateListener(animation -> {
            if (quietTransition != animation) return;
            if (!CameraObservationGate.isFresh(lastSleepFrameAt, SystemClock.elapsedRealtime())) {
                abortQuietTransition();
                quietRetryPending = true;
                return;
            }
            float fraction = (float) animation.getAnimatedValue();
            setPlaybackLevels(brightnessBeforeTransition + (DIM_BRIGHTNESS - brightnessBeforeTransition) * fraction,
                    volumeBeforeTransition + (QUIET_VOLUME - volumeBeforeTransition) * fraction);
            if (fraction >= 1f) {
                quiet = true;
                quietTransition = null;
                mediaLabel.setText("잠든 것으로 보여 소리와 화면을 낮췄습니다.");
            }
        });
        quietTransition = transition;
        transition.start();
    }

    private void stopQuietTransition() {
        quietRetryPending = false;
        ValueAnimator pending = quietTransition;
        quietTransition = null;
        if (pending != null) pending.cancel();
    }

    private void abortQuietTransition() {
        boolean wasAdjusting = quietTransition != null;
        stopQuietTransition();
        if (wasAdjusting) setPlaybackLevels(brightnessBeforeTransition, volumeBeforeTransition);
    }

    private void render(long now) {
        Result result = engine.snapshot(now);
        if (result.sleepState != SleepState.ASLEEP) abortQuietTransition();
        if (recording != null && now - lastTripCheckpoint >= TRIP_CHECKPOINT_MS) {
            checkpointTrip(now, result);
        }
        cachedState = stateJson(result);
        if (!active) return;
        updateScreenLockButton();
        String title;
        switch (result.sleepState) {
            case ASLEEP: title = "잠든 것으로 추정"; break;
            case FALLING_ASLEEP: title = "잠드는지 살피는 중"; break;
            case AWAKE: title = "깨어 있는 것으로 추정"; break;
            default: title = "상태를 확인할 수 없음";
        }
        stateLabel.setText(title);
        detailLabel.setText(getString(R.string.observation_summary,
                scenario ? "시연 입력" : "전면 카메라 관찰", formatDuration(result.totalSleepMs)));
        StringBuilder careNotice = new StringBuilder();
        if (!scenario && result.sleepState == SleepState.UNKNOWN) {
            careNotice.append("카메라 위치와 연결을 확인해 주세요.\n");
        }
        for (int i = result.alerts.size() - 1; i >= 0; i--) {
            Alert alert = result.alerts.get(i);
            if (switch (alert.kind) {
                case VISIBILITY -> false;
                case SLEEP -> result.sleepState == SleepState.ASLEEP;
                case WAKE -> result.sleepState == SleepState.AWAKE;
                case POSTURE, VOMIT_SUSPECTED -> true;
            }) {
                careNotice.append(alert.message);
                break;
            }
        }
        String careText = careNotice.toString().trim();
        careNoticeLabel.setText(careText);
        careNoticeLabel.setVisibility(careText.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String stateJson(Result result) {
        try {
            JSONObject state = new JSONObject();
            state.put("connected", observationOpen());
            state.put("backgroundObservation", backgroundObservation);
            state.put("appVisible", active);
            boolean screenInteractive = getSystemService(PowerManager.class).isInteractive();
            state.put("screenInteractive", screenInteractive);
            state.put("screenLockAllowed", screenInteractive && screenLockObservationReady()
                    && screenLockPermissionGranted());
            state.put("source", scenario ? "scenario" : "camera");
            state.put("sleepState", result.sleepState.name());
            state.put("postureState", result.postureState.name());
            state.put("totalSleepMs", result.totalSleepMs);
            state.put("sleepStartedAtMs", result.sleepStartedAtMs);
            state.put("cameraVisible", !scenario && cameraVisible);
            state.put("cameraReady", !scenario && cameraReady);
            state.put("cameraFrameSequence", cameraFrameSequence);
            state.put("previewShared", active && !scenario && sharePreview
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED);
            JSONArray alerts = new JSONArray();
            for (Alert alert : result.alerts) {
                JSONObject item = new JSONObject();
                item.put("id", alert.id);
                item.put("kind", alert.kind.name());
                item.put("message", alert.message);
                item.put("timestampMs", alert.timestampMs);
                alerts.put(item);
            }
            state.put("alerts", alerts);
            JSONObject mediaState = new JSONObject();
            mediaState.put("playing", active && video.isPlaying());
            mediaState.put("available", active && videoUri != null && media != null);
            mediaState.put("quiet", quiet);
            mediaState.put("brightness", brightness);
            mediaState.put("volume", volume);
            state.put("media", mediaState);
            return state.toString();
        } catch (JSONException invalidState) {
            notice("상태 정보를 전달할 수 없습니다. 연결을 다시 시도해 주세요.");
            return "{\"connected\":false,\"error\":\"state_unavailable\"}";
        }
    }

    private void startPeer() {
        String sessionToken = PeerTls.newToken();
        token = sessionToken;
        pairingLabel.setText("안전한 연결 준비 중");
        pairingExecutor.execute(() -> {
            try {
                PeerTls tls = PeerTls.load();
                main.post(() -> publishPeer(tls, sessionToken));
            } catch (GeneralSecurityException | IOException | ProviderException failed) {
                main.post(() -> {
                    if (observationOpen() && sessionToken.equals(token)) {
                        pairingLabel.setText("안전한 연결을 준비하지 못했습니다. 이 화면을 다시 열어 주세요.");
                    }
                });
            }
        });
    }

    private void publishPeer(PeerTls tls, String sessionToken) {
        if (!observationOpen() || !sessionToken.equals(token)) return;
        PeerServer server = new PeerServer(tls, sessionToken, () -> cachedState, this::previewForPeer,
                action -> remoteCommand(action, sessionToken));
        try {
            server.start(5000, false);
            peer = server;
            pairingLabel.setText(getString(R.string.pairing_details, localAddress(), tls.pairingCode(sessionToken)));
        } catch (IOException bindFailed) {
            server.stop();
            pairingLabel.setText("연결 포트를 열 수 없습니다. 이 화면을 다시 열어 주세요.");
        }
    }

    private String remoteCommand(String action, String sessionToken) {
        if ("KIDS_PAUSE".equals(action) || "KIDS_RESUME".equals(action)) {
            return remoteKidsCommand("KIDS_RESUME".equals(action), sessionToken);
        }
        FutureTask<String> task = new FutureTask<>(() -> {
            if (!observationOpen() || !sessionToken.equals(token)) return null;
            if (!active && !"LOCK_SCREEN".equals(action)) {
                return "{\"ok\":false,\"error\":\"foreground_required\"}";
            }
            String error = applyCommand(action);
            if (error != null) return "{\"ok\":false,\"error\":\"" + error + "\"}";
            render(SystemClock.elapsedRealtime());
            return "{\"ok\":true,\"state\":" + cachedState + "}";
        });
        main.post(task);
        try {
            return task.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            task.cancel(false);
            return null;
        } catch (TimeoutException timeout) {
            task.cancel(false);
            return null;
        } catch (ExecutionException failedCommand) {
            return null;
        }
    }

    private String remoteKidsCommand(boolean play, String sessionToken) {
        CompletableFuture<String> result = new CompletableFuture<>();
        main.post(() -> {
            if (result.isDone()) return;
            if (!observationOpen() || !sessionToken.equals(token)) {
                result.complete(null);
                return;
            }
            if (!kidsControl.isChecked()) {
                result.complete("{\"ok\":false,\"error\":\"kids_control_disabled\"}");
                return;
            }
            kidsPlaybackController.request(play,
                    () -> !result.isDone() && kidsControl.isChecked() && sessionToken.equals(token)
                            && observationOpen() && !scenario && !screenLockedOrOff()
                            && (active || (otherAppObservationSwitch.isChecked()
                                    && backgroundObservationAllowed(SystemClock.elapsedRealtime()))),
                    error -> result.complete(error == null
                            ? "{\"ok\":true,\"kidsPlayback\":{\"playing\":" + play + "}}"
                            : "{\"ok\":false,\"error\":\"" + error + "\"}"));
        });
        try {
            return result.get(3200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException unconfirmed) {
            // No confirmed playback state arrived before the peer deadline.
        }
        result.cancel(false);
        return "{\"ok\":false,\"error\":\"kids_state_not_confirmed\"}";
    }

    private String applyCommand(String action) {
        quietRetryPending = false;
        if ("LOCK_SCREEN".equals(action)) {
            return lockScreen();
        } else if ("QUIET".equals(action)) {
            stopQuietTransition();
            quiet = true;
            setPlaybackLevels(DIM_BRIGHTNESS, QUIET_VOLUME);
            mediaLabel.setText("소리와 화면을 줄였습니다.");
        } else if ("DIM".equals(action)) {
            abortQuietTransition();
            setPlaybackLevels(DIM_BRIGHTNESS, volume);
            mediaLabel.setText("이 앱의 화면을 어둡게 했습니다.");
        } else if ("PAUSE".equals(action) || "RESUME".equals(action)) {
            if (videoUri == null || media == null) {
                mediaLabel.setText("먼저 이 기기에서 영상을 선택해 주세요.");
                return "media_not_ready";
            }
            if ("PAUSE".equals(action)) {
                abortQuietTransition();
                video.pause();
                mediaLabel.setText("영상을 멈췄습니다.");
                return null;
            }
            stopQuietTransition();
            quiet = false;
            setPlaybackLevels(NORMAL_BRIGHTNESS, 1f);
            video.start();
            mediaLabel.setText("선택한 영상을 재생합니다.");
        } else if ("RESET".equals(action)) {
            if (recording != null) return "trip_in_progress";
            abortQuietTransition();
            cameraObservation.invalidate();
            engine.reset();
            notice("이동 기록과 기준 자세를 초기화했습니다.");
        }
        return null;
    }

    private String lockScreen() {
        if (!screenLockPermissionGranted()) return "lock_permission_required";
        if (!screenLockObservationReady() || !getSystemService(PowerManager.class).isInteractive()) {
            return "background_observation_required";
        }
        previewShare.setChecked(false);
        try {
            screenLockPolicy.lockNow();
            abortQuietTransition();
            return null;
        } catch (SecurityException denied) {
            return "lock_permission_required";
        }
    }

    private void setPlaybackLevels(float newBrightness, float newVolume) {
        brightness = newBrightness;
        volume = newVolume;
        if (media != null) media.setVolume(volume, volume);
        setBrightness();
    }

    private void setBrightness() {
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = brightness;
        getWindow().setAttributes(attributes);
    }

    private void chooseVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, VIDEO_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SCREEN_LOCK_REQUEST) {
            updateScreenLockButton();
        } else if (requestCode == VIDEO_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            media = null;
            videoUri = data.getData();
            if (active) video.setVideoURI(videoUri);
            mediaLabel.setText("영상을 준비하고 있습니다.");
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else if (requestCode == CAMERA_REQUEST) {
            notice("카메라 권한이 필요합니다. 시연 입력은 권한 없이 사용할 수 있습니다.");
        }
    }

    private void capturePreview(long now) {
        lastPreviewAt = now;
        int generation = previewGeneration;
        Bitmap bitmap = preview.getBitmap();
        if (bitmap == null) return;
        int width = Math.min(640, bitmap.getWidth());
        int height = Math.max(1, bitmap.getHeight() * width / bitmap.getWidth());
        Bitmap small = Bitmap.createScaledBitmap(bitmap, width, height, true);
        if (small != bitmap) bitmap.recycle();
        cameraExecutor.execute(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean encoded = small.compress(Bitmap.CompressFormat.JPEG, 65, out);
            small.recycle();
            if (!encoded) return;
            PreviewFrame frame = new PreviewFrame(out.toByteArray(), now);
            main.post(() -> {
                if (active && sharePreview && generation == previewGeneration) sharedPreview = frame;
            });
        });
    }

    private byte[] previewForPeer() {
        PreviewFrame frame = sharedPreview;
        return active && sharePreview && !scenario && frame != null
                && SystemClock.elapsedRealtime() - frame.capturedAt < 2500 ? frame.jpeg : null;
    }

    private String localAddress() {
        try {
            List<NetworkInterface> networks = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface network : networks) {
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress() && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException unavailable) {
            notice("로컬 주소를 찾을 수 없습니다. Wi-Fi 연결을 확인해 주세요.");
        }
        return "로컬 주소 없음";
    }

    private String formatDuration(long milliseconds) {
        long seconds = milliseconds / 1000;
        return String.format(Locale.KOREA, "%d분 %02d초", seconds / 60, seconds % 60);
    }

    private void notice(String message) { if (noticeLabel != null) noticeLabel.setText(message); }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setPadding(0, dp(7), 0, dp(9));
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private Button button(String text, Runnable action) {
        Button view = new Button(this);
        view.setText(text);
        view.setTextColor(INK);
        view.setTextSize(13);
        view.setMinHeight(dp(48));
        view.setAllCaps(false);
        view.setOnClickListener(ignored -> action.run());
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1f); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
