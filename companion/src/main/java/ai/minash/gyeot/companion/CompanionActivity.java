package ai.minash.gyeot.companion;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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

import ai.minash.gyeot.core.CareEngine;
import ai.minash.gyeot.core.CareEngine.Alert;
import ai.minash.gyeot.core.CareEngine.Config;
import ai.minash.gyeot.core.CareEngine.Frame;
import ai.minash.gyeot.core.CareEngine.Result;

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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class CompanionActivity extends ComponentActivity {
    private static final int CAMERA_REQUEST = 11;
    private static final int VIDEO_REQUEST = 12;
    private static final int BACKGROUND = Color.rgb(14, 22, 28);
    private static final int INK = Color.rgb(235, 242, 239);
    private static final int MUTED = Color.rgb(165, 184, 181);
    private static final int ACCENT = Color.rgb(127, 216, 183);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final CareEngine engine = new CareEngine(new Config(8000, 2000, 2500, 2000, .28f, 28f));
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
    private TextView noticeLabel;
    private TextView mediaLabel;
    private Switch previewShare;
    private LinearLayout scenarios;
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis analysis;
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
    private float brightness = .85f;
    private long lastCameraFrame;
    private long lastAnalyzed;
    private long lastPreviewAt;
    private volatile int cameraGeneration;
    private String token;
    private volatile String cachedState = "{\"connected\":false}";
    private volatile PreviewFrame sharedPreview;
    private volatile boolean sharePreview;
    private int previewGeneration;

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
            if (!active) return;
            long now = SystemClock.elapsedRealtime();
            if (scenario) {
                accept(new Frame(now, scenarioVisible, scenarioClosed ? .05f : .95f,
                        scenarioClosed ? .05f : .95f, scenarioPosture ? 45 : 0, 0));
            } else if (now - lastCameraFrame > 2000) {
                cameraVisible = false;
                cameraReady = false;
                accept(new Frame(now, false, -1, -1, 0, 0));
            }
            render(now);
            if (sharePreview && now - lastPreviewAt > 1500
                    && preview.getPreviewStreamState().getValue() == PreviewView.StreamState.STREAMING) {
                capturePreview(now);
            }
            main.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildScreen();
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        token = UUID.randomUUID().toString();
        startPeer();
        lastCameraFrame = SystemClock.elapsedRealtime();
        if (!scenario) startCamera();
        if (videoUri != null) video.setVideoURI(videoUri);
        main.post(tick);
    }

    @Override protected void onPause() {
        active = false;
        main.removeCallbacks(tick);
        stopCamera();
        cameraVisible = false;
        cameraReady = false;
        previewShare.setChecked(false);
        video.stopPlayback();
        media = null;
        if (peer != null) { peer.stop(); peer = null; }
        accept(new Frame(SystemClock.elapsedRealtime(), false, -1, -1, 0, 0));
        cachedState = "{\"connected\":false}";
        super.onPause();
    }

    @Override protected void onDestroy() {
        detector.close();
        cameraExecutor.shutdown();
        video.stopPlayback();
        super.onDestroy();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(44), dp(22), dp(30));
        scroll.addView(body);
        body.addView(label("곁  /  뒷좌석 연결", 26, INK));
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
            engine.reset();
            notice("다음에 확인되는 얼굴을 기준 자세로 사용합니다.");
        }));
        Switch automatic = new Switch(this);
        automatic.setText("잠듦 추정 시 소리와 화면 자동으로 줄이기");
        automatic.setTextColor(INK);
        automatic.setTextSize(14);
        automatic.setChecked(true);
        automatic.setPadding(0, dp(8), 0, dp(8));
        automatic.setOnCheckedChangeListener((view, enabled) -> autoQuiet = enabled);
        body.addView(automatic);
        body.addView(label("이 기기에서 재생할 영상", 18, INK));
        body.addView(button("기기의 영상 선택", this::chooseVideo));
        video = new VideoView(this);
        video.setContentDescription("선택한 로컬 영상 플레이어");
        body.addView(video, new LinearLayout.LayoutParams(-1, dp(190)));
        video.setOnPreparedListener(player -> {
            media = player;
            player.setLooping(true);
            player.setVolume(quiet ? .12f : 1f, quiet ? .12f : 1f);
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
        body.addView(label("같은 신뢰할 수 있는 로컬 네트워크에서 주소와 연결 키를 입력하세요. 화면을 벗어나면 연결과 관찰이 중단되고 키가 바뀝니다.", 13, MUTED));
        previewShare = new Switch(this);
        previewShare.setText("차량에 카메라 미리보기 공유");
        previewShare.setTextColor(INK);
        previewShare.setTextSize(14);
        previewShare.setMinHeight(dp(48));
        previewShare.setOnCheckedChangeListener((view, enabled) -> {
            sharePreview = enabled;
            previewGeneration++;
            sharedPreview = null;
        });
        body.addView(previewShare);
        Switch scenarioSwitch = new Switch(this);
        scenarioSwitch.setText("시연 입력 사용");
        scenarioSwitch.setTextColor(INK);
        scenarioSwitch.setTextSize(16);
        scenarioSwitch.setPadding(0, dp(20), 0, dp(10));
        scenarioSwitch.setOnCheckedChangeListener((view, enabled) -> setScenario(enabled));
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
        noticeLabel = label("얼굴과 눈 정보를 이용한 추정입니다. 구토와 호흡 상태를 자동 판정하지 않습니다.", 14, MUTED);
        body.addView(noticeLabel);
        setContentView(scroll);
    }

    private void setScenario(boolean enabled) {
        scenario = enabled;
        scenarioVisible = true;
        scenarioClosed = false;
        scenarioPosture = false;
        cameraVisible = false;
        cameraReady = false;
        previewShare.setChecked(false);
        previewShare.setEnabled(!enabled);
        engine.reset();
        stopCamera();
        scenarios.setVisibility(enabled ? View.VISIBLE : View.GONE);
        preview.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled) accept(new Frame(SystemClock.elapsedRealtime(), true, .95f, .95f, 0, 0));
        if (!enabled && active) startCamera();
        notice(enabled ? "시연 입력을 사용합니다." : "전면 카메라에서 관찰합니다.");
    }

    private void setScenarioFrame(boolean visible, boolean closed, boolean posture) {
        scenarioVisible = visible;
        scenarioClosed = closed;
        scenarioPosture = posture;
        notice("시연 입력을 바꿨습니다. 상태가 지속되면 알림에 반영됩니다.");
    }

    private void startCamera() {
        if (!active || scenario) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
            return;
        }
        final int generation = ++cameraGeneration;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (!active || scenario || generation != cameraGeneration) return;
            try {
                cameraProvider = future.get();
                Preview cameraPreview = new Preview.Builder().build();
                cameraPreview.setSurfaceProvider(preview.getSurfaceProvider());
                analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(cameraExecutor, image -> analyze(image, generation));
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, cameraPreview, analysis);
                notice("전면 카메라 관찰 중. 한 명의 얼굴이 보이도록 맞춰 주세요.");
            } catch (ExecutionException failedStart) {
                notice("카메라를 시작할 수 없습니다. 시연 입력으로 흐름을 확인할 수 있습니다.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                notice("카메라 준비가 중단됐습니다.");
            } catch (IllegalArgumentException | IllegalStateException | SecurityException unavailable) {
                notice("전면 카메라를 사용할 수 없습니다. 권한과 기기 상태를 확인해 주세요.");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void stopCamera() {
        cameraGeneration++;
        if (analysis != null) { analysis.clearAnalyzer(); analysis = null; }
        if (cameraProvider != null) cameraProvider.unbindAll();
    }

    @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
    private void analyze(ImageProxy image, int generation) {
        long now = SystemClock.elapsedRealtime();
        if (!active || scenario || generation != cameraGeneration || now - lastAnalyzed < 250) {
            image.close();
            return;
        }
        lastAnalyzed = now;
        if (image.getImage() == null) { image.close(); return; }
        InputImage input = InputImage.fromMediaImage(image.getImage(), image.getImageInfo().getRotationDegrees());
        detector.process(input).addOnSuccessListener(ContextCompat.getMainExecutor(this), faces -> {
            if (!active || scenario || generation != cameraGeneration) return;
            lastCameraFrame = SystemClock.elapsedRealtime();
            cameraReady = true;
            cameraVisible = faces.size() == 1;
            if (!cameraVisible) {
                accept(new Frame(lastCameraFrame, false, -1, -1, 0, 0));
                return;
            }
            Face face = faces.get(0);
            float pitch = face.getHeadEulerAngleX();
            float roll = face.getHeadEulerAngleZ();
            Float left = face.getLeftEyeOpenProbability();
            Float right = face.getRightEyeOpenProbability();
            boolean frontal = Math.abs(face.getHeadEulerAngleY()) <= 18;
            accept(new Frame(lastCameraFrame, true, frontal && left != null ? left : -1,
                    frontal && right != null ? right : -1, pitch, roll));
        }).addOnFailureListener(ContextCompat.getMainExecutor(this), failure -> {
            if (!active || generation != cameraGeneration) return;
            cameraVisible = false;
            cameraReady = false;
            lastCameraFrame = SystemClock.elapsedRealtime();
            accept(new Frame(lastCameraFrame, false, -1, -1, 0, 0));
            notice("영상 분석이 중단됐습니다. 얼굴과 조명을 확인해 주세요.");
        }).addOnCompleteListener(task -> image.close());
    }

    private void accept(Frame frame) {
        Result result = engine.accept(frame);
        if (active && autoQuiet && result.requestQuiet && !quiet) applyCommand("QUIET");
    }

    private void render(long now) {
        Result result = engine.snapshot(now);
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
        if (!result.alerts.isEmpty()) {
            Alert latest = result.alerts.get(result.alerts.size() - 1);
            noticeLabel.setText(latest.message);
        }
        cachedState = stateJson(result);
    }

    private String stateJson(Result result) {
        try {
            JSONObject state = new JSONObject();
            state.put("connected", active);
            state.put("source", scenario ? "scenario" : "camera");
            state.put("sleepState", result.sleepState.name());
            state.put("totalSleepMs", result.totalSleepMs);
            state.put("sleepStartedAtMs", result.sleepStartedAtMs);
            state.put("cameraVisible", !scenario && cameraVisible);
            state.put("cameraReady", !scenario && cameraReady);
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
            mediaState.put("playing", video.isPlaying());
            mediaState.put("quiet", quiet);
            mediaState.put("brightness", brightness);
            state.put("media", mediaState);
            return state.toString();
        } catch (JSONException invalidState) {
            notice("상태 정보를 전달할 수 없습니다. 연결을 다시 시도해 주세요.");
            return "{\"connected\":false,\"error\":\"state_unavailable\"}";
        }
    }

    private void startPeer() {
        final String sessionToken = token;
        peer = new PeerServer(sessionToken, () -> cachedState, this::previewForPeer,
                action -> remoteCommand(action, sessionToken));
        try {
            peer.start(5000, false);
            pairingLabel.setText(getString(R.string.pairing_details, localAddress(), token));
        } catch (IOException bindFailed) {
            peer = null;
            pairingLabel.setText("연결 포트를 열 수 없습니다. 이 화면을 다시 열어 주세요.");
        }
    }

    private String remoteCommand(String action, String sessionToken) {
        FutureTask<String> task = new FutureTask<>(() -> {
            if (!active || !sessionToken.equals(token)) return null;
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

    private String applyCommand(String action) {
        if ("QUIET".equals(action)) {
            quiet = true;
            brightness = .18f;
            if (media != null) media.setVolume(.12f, .12f);
            setBrightness();
            mediaLabel.setText("소리와 화면을 줄였습니다.");
        } else if ("PAUSE".equals(action)) {
            video.pause();
            mediaLabel.setText("영상을 멈췄습니다.");
        } else if ("RESUME".equals(action)) {
            if (videoUri == null || media == null) {
                mediaLabel.setText("먼저 이 기기에서 영상을 선택해 주세요.");
                return "media_not_ready";
            }
            quiet = false;
            brightness = .85f;
            media.setVolume(1f, 1f);
            setBrightness();
            video.start();
            mediaLabel.setText("선택한 영상을 재생합니다.");
        } else if ("RESET".equals(action)) {
            engine.reset();
            notice("이동 기록과 기준 자세를 초기화했습니다.");
        }
        return null;
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
        if (requestCode == VIDEO_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
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
