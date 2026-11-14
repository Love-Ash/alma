package ai.minash.gyeot.companion;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import ai.minash.gyeot.companion.TripSleepStore.Span;
import ai.minash.gyeot.companion.TripSleepStore.Trip;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class PeerIntegrationRunner extends Instrumentation {
    private static final Pattern PAIRING_CODE = Pattern.compile("g1\\.([A-Za-z0-9_-]{43})\\.([A-Za-z0-9_-]{22})");
    private static final String SYNTHETIC_PLAYER = "ai.minash.gyeot.kidsfixture";
    private static final String SYNTHETIC_ACTIVITY = SYNTHETIC_PLAYER + "/.FixtureActivity";
    private static final String KIDS_CONTROL = "차량에서 YouTube Kids 재생 제어";
    private static final String OTHER_APP_OBSERVATION = "다른 앱을 쓰는 동안 관찰 계속하기";
    private static final Pattern PLAYER_EVENT = Pattern.compile(
            "(PLAYER|FOREGROUND_SAMPLE) playing=(true|false) positionMs=(\\d+)");
    private SSLSocketFactory pinnedSocketFactory;
    private CompanionActivity activity;
    private int passed;
    private boolean cameraFixture;
    private boolean postureProbe;
    private boolean kidsTlsSynthetic;
    private String autoQuietVideo;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        cameraFixture = arguments != null && Boolean.parseBoolean(arguments.getString("cameraFixture"));
        postureProbe = arguments != null && Boolean.parseBoolean(arguments.getString("postureProbe"));
        kidsTlsSynthetic = arguments != null && Boolean.parseBoolean(arguments.getString("kidsTlsSynthetic"));
        autoQuietVideo = arguments == null ? null : arguments.getString("autoQuietVideo");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (postureProbe) {
                result.putString("stream", probePostureSamples());
                finish(Activity.RESULT_OK, result);
                return;
            }
            Intent intent = new Intent(getTargetContext(), CompanionActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (CompanionActivity) startActivitySync(intent);
            waitForIdleSync();
            Pairing pairing = awaitPairingCode();
            pinnedSocketFactory = socketFactory(pairing.fingerprint, () -> {});
            String token = pairing.token;

            if (kidsTlsSynthetic) {
                testSyntheticKidsTls(token);
                result.putString("stream", "COMPANION_SYNTHETIC_KIDS_TLS_CHECKS_PASSED checks=" + passed + "\n");
                finish(Activity.RESULT_OK, result);
                return;
            }

            if (autoQuietVideo != null) {
                testAutoQuietInterruption(token);
                result.putString("stream", "COMPANION_AUTO_QUIET_CHECKS_PASSED checks=" + passed + "\n");
                finish(Activity.RESULT_OK, result);
                return;
            }

            check(request("GET", "/state", null, null).status == 401, "state rejects missing token");
            check(request("GET", "/state", "invalid", null).status == 401, "state rejects wrong token");
            JSONObject initial = awaitState(token, "connected", "true", 3000);
            check(!initial.getBoolean("backgroundObservation"), "background camera observation defaults off");
            check("camera".equals(initial.getString("source")),
                    "authenticated state through pinned TLS");
            testTransport(pairing);
            check(request("GET", "/preview.jpg", null, null).status == 401,
                    "preview requires authentication");
            check(request("GET", "/preview.jpg", token, null).status == 404,
                    "authenticated preview is off by default");
            if (cameraFixture) testCameraPreview(token);
            testTripRecords(token);
            check(request("GET", "/missing", token, null).status == 404, "unknown route rejected");
            check(request("POST", "/command", token, "{bad").status == 400, "malformed command rejected");
            check(request("POST", "/command", token, "{\"action\":\"ERASE\"}").status == 400,
                    "unknown action rejected");
            char[] oversized = new char[5000];
            java.util.Arrays.fill(oversized, 'x');
            check(request("POST", "/command", token, new String(oversized)).status == 400,
                    "oversized command rejected");
            Reply pause = request("POST", "/command", token, "{\"action\":\"PAUSE\"}");
            check(pause.status == 200 && !new JSONObject(pause.body).getBoolean("ok"),
                    "pause without selected video reports failure");
            Reply resume = request("POST", "/command", token, "{\"action\":\"RESUME\"}");
            check(resume.status == 200 && !new JSONObject(resume.body).getBoolean("ok"),
                    "resume without selected video reports failure");

            setSwitch("시연 입력 사용", true);
            JSONObject scenario = awaitState(token, "source", "scenario", 3000);
            check(!scenario.getBoolean("cameraVisible"), "scenario never claims camera observation");
            runOnMainSync(() -> click("눈 감김"));
            JSONObject sleeping = awaitState(token, "sleepState", "ASLEEP", 12000);
            check(sleeping.getLong("totalSleepMs") >= 8000, "sustained scenario closes eyes into sleep estimate");
            check(sleeping.getJSONObject("media").getDouble("brightness") > .2,
                    "automatic brightness starts a gradual transition");
            long deadline = SystemClock.elapsedRealtime() + 4000;
            boolean quiet = false;
            while (!quiet && SystemClock.elapsedRealtime() < deadline) {
                quiet = new JSONObject(request("GET", "/state", token, null).body)
                        .getJSONObject("media").getBoolean("quiet");
                if (!quiet) Thread.sleep(100);
            }
            check(quiet, "automatic transition reaches quiet mode");
            runOnMainSync(() -> click("눈 뜸"));
            awaitState(token, "sleepState", "AWAKE", 5000);
            awaitState(token, "postureState", "OBSERVED", 5000);
            check(!new JSONObject(request("GET", "/state", token, null).body)
                    .getJSONObject("media").getBoolean("playing"), "wake never starts media automatically");
            JSONObject dim = new JSONObject(request("POST", "/command", token, "{\"action\":\"DIM\"}").body);
            check(dim.getBoolean("ok") && dim.getJSONObject("state").getJSONObject("media").getDouble("brightness") < .2,
                    "app display dimming returns the applied brightness");
            runOnMainSync(() -> click("자세 변화"));
            awaitAlert(token, "POSTURE", 5000);
            check(true, "sustained scenario posture change raises alert");
            runOnMainSync(() -> click("구토 의심 사건 넣기"));
            awaitAlert(token, "VOMIT_SUSPECTED", 3000);
            check(true, "explicit scenario vomiting event appears as suspected");
            check(request("GET", "/preview.jpg", token, null).status == 404,
                    "scenario cannot expose stale camera preview");

            testCommandTimeout(token);
            runOnMainSync(() -> callActivityOnPause(activity));
            try {
                request("GET", "/state", token, null);
                throw new AssertionError("endpoint remains available after pause");
            } catch (IOException expectedStop) {
                check(true, "pause closes TLS endpoint");
            }
            runOnMainSync(() -> callActivityOnResume(activity));
            Pairing resumed = awaitPairingCode();
            String newToken = resumed.token;
            check(!newToken.equals(token) && Arrays.equals(resumed.fingerprint, pairing.fingerprint),
                    "resume keeps certificate and rotates pairing token");
            check(request("GET", "/state", token, null).status == 401, "old session token rejected");
            check(request("GET", "/preview.jpg", newToken, null).status == 404,
                    "preview stays unavailable after foreground session changes");
            awaitState(newToken, "source", "scenario", 3000);
            check(new JSONObject(request("GET", "/state", newToken, null).body).getBoolean("connected"),
                    "resumed foreground session serves fresh state");

            result.putString("stream", "COMPANION_HTTP_CHECKS_PASSED checks=" + passed + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError failure) {
            result.putString("stream", "COMPANION_HTTP_CHECKS_FAILED after=" + passed + " "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage() + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void testSyntheticKidsTls(String token) throws Exception {
        require(Build.VERSION.SDK_INT == 34 && Build.PRODUCT.startsWith("sdk_")
                        && (getTargetContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                "synthetic Kids suite requires a debug Android 14 emulator");
        Field controllerField = CompanionActivity.class.getDeclaredField("kidsPlaybackController");
        controllerField.setAccessible(true);
        AtomicReference<IllegalAccessException> injectionFailure = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                controllerField.set(activity, new KidsPlaybackController(activity,
                        new Handler(Looper.getMainLooper()), SYNTHETIC_PLAYER));
            } catch (IllegalAccessException failure) {
                injectionFailure.set(failure);
            }
        });
        if (injectionFailure.get() != null) throw injectionFailure.get();

        ComponentName listener = new ComponentName(activity, KidsSessionAccess.class);
        boolean originalAccess = notificationAccess(listener);
        try {
            awaitState(token, "cameraReady", "true", 18000);
            check(request("POST", "/command", null, "{\"action\":\"KIDS_PAUSE\"}").status == 401,
                    "Kids command requires an authenticated pairing");
            checkKidsFailure(token, "KIDS_PAUSE", "kids_control_disabled");
            setSwitch(OTHER_APP_OBSERVATION, true);
            awaitState(token, "backgroundObservation", "true", 8000);
            setNotificationAccess(listener, true);
            setSwitch(KIDS_CONTROL, true);

            shell("am force-stop " + SYNTHETIC_PLAYER);
            String launch = shell("am start -W -n " + SYNTHETIC_ACTIVITY + " --ez foreground_only true");
            require(launch.contains("Status: ok"), "synthetic foreground-only player did not launch");
            String pid = shell("pidof " + SYNTHETIC_PLAYER).trim();
            require(pid.matches("\\d+"), "synthetic player PID unavailable");
            checkPlayerForeground();
            JSONObject background = awaitState(token, "appVisible", "false", 5000);
            check(background.getBoolean("backgroundObservation") && background.getBoolean("screenInteractive"),
                    "observation remains active behind the interactive synthetic player");
            check(request("GET", "/preview.jpg", token, null).status == 404,
                    "background observation leaves JPEG sharing closed");
            int initialPosition = playerEvents(pid).stream().filter(event -> !event.sample && event.playing)
                    .findFirst().orElseThrow(() -> new AssertionError("missing initial MediaPlayer start")).positionMs;
            checkKidsFailure(token, "PAUSE", "foreground_required");
            check(!mediaState(token).getBoolean("available"), "local media remains unavailable in the background");

            int beforePause = playerEvents(pid).size();
            checkKidsSuccess(token, "KIDS_PAUSE", false);
            int pausedPosition = awaitPlayerPosition(pid, beforePause, false);
            check(pausedPosition > initialPosition, "independent MediaPlayer advanced before the pause");
            checkKidsFailure(token, "KIDS_PAUSE", "kids_start_state_mismatch");
            int beforeResume = playerEvents(pid).size();
            checkKidsSuccess(token, "KIDS_RESUME", true);
            awaitPlayerPosition(pid, beforeResume, true);
            checkPlayerForeground();
            JSONObject current = awaitState(token, "cameraReady", "true", 5000);
            check(!current.getBoolean("appVisible") && current.getBoolean("backgroundObservation")
                            && current.getLong("cameraFrameSequence") > background.getLong("cameraFrameSequence"),
                    "CameraX frames advanced on the same authenticated session during fullscreen playback");

            setNotificationAccess(listener, false);
            checkKidsFailure(token, "KIDS_PAUSE", "kids_access_required");
            checkPlayerForeground();
            List<PlayerEvent> afterRevocation = playerEvents(pid);
            check(!afterRevocation.isEmpty() && afterRevocation.get(afterRevocation.size() - 1).playing,
                    "revoked notification access did not pause the player");
        } finally {
            try {
                runOnMainSync(activity::finish);
            } finally {
                try {
                    shell("am force-stop " + SYNTHETIC_PLAYER);
                } finally {
                    setNotificationAccess(listener, originalAccess);
                }
            }
        }
    }

    private void checkKidsFailure(String token, String action, String error) throws Exception {
        JSONObject response = kidsReply(token, action);
        check(!response.optBoolean("ok", true) && error.equals(response.optString("error")),
                action + " reports " + error + " (received " + response.optString("error") + ")");
    }

    private void checkKidsSuccess(String token, String action, boolean playing) throws Exception {
        JSONObject response = kidsReply(token, action);
        JSONObject playback = response.optJSONObject("kidsPlayback");
        check(response.optBoolean("ok") && playback != null && Boolean.valueOf(playing).equals(playback.opt("playing")),
                action + " confirms target playing=" + playing + " (error " + response.optString("error") + ")");
    }

    private JSONObject kidsReply(String token, String action) throws Exception {
        Reply reply = request("POST", "/command", token, "{\"action\":\"" + action + "\"}");
        require(reply.status == 200, action + " returned HTTP " + reply.status);
        return new JSONObject(reply.body);
    }

    private void checkPlayerForeground() throws IOException {
        String[] activities = shell("dumpsys activity activities").split("\\R");
        boolean top = Arrays.stream(activities).anyMatch(line -> line.contains("topResumedActivity")
                && line.contains(SYNTHETIC_ACTIVITY));
        boolean fullscreen = Arrays.stream(activities).anyMatch(line -> line.trim().startsWith("* Task{")
                && line.contains(":" + SYNTHETIC_PLAYER + " ") && line.contains("visible=true ")
                && line.contains("mode=fullscreen "));
        check(top && fullscreen, "synthetic player remains the visible, top-resumed fullscreen app");
    }

    private record PlayerEvent(boolean sample, boolean playing, int positionMs) {}

    private List<PlayerEvent> playerEvents(String pid) throws IOException {
        String log = shell("logcat -d --pid=" + pid + " -s GyeotKidsFixture:I");
        List<PlayerEvent> events = new ArrayList<>();
        Matcher matcher = PLAYER_EVENT.matcher(log);
        while (matcher.find()) {
            events.add(new PlayerEvent("FOREGROUND_SAMPLE".equals(matcher.group(1)),
                    Boolean.parseBoolean(matcher.group(2)), Integer.parseInt(matcher.group(3))));
        }
        return events;
    }

    private int awaitPlayerPosition(String pid, int from, boolean playing) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < deadline) {
            List<PlayerEvent> events = playerEvents(pid);
            PlayerEvent transition = null;
            for (int index = from; index < events.size(); index++) {
                PlayerEvent event = events.get(index);
                if (!event.sample) {
                    transition = event.playing == playing ? event : null;
                } else if (transition != null && event.playing == playing) {
                    int delta = event.positionMs - transition.positionMs;
                    if (playing ? delta > 0 : delta == 0) {
                        check(true, "independent MediaPlayer playing=" + playing + " positionMs="
                                + transition.positionMs + "->" + event.positionMs);
                        return event.positionMs;
                    }
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("independent MediaPlayer position did not confirm playing=" + playing);
    }

    private boolean notificationAccess(ComponentName listener) {
        return getTargetContext().getSystemService(NotificationManager.class)
                .isNotificationListenerAccessGranted(listener);
    }

    private void setNotificationAccess(ComponentName listener, boolean allowed) throws Exception {
        if (notificationAccess(listener) == allowed) return;
        String command = allowed ? "allow_listener" : "disallow_listener";
        shell("cmd notification " + command + " " + listener.flattenToShortString() + " 0");
        long deadline = SystemClock.elapsedRealtime() + 3000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (notificationAccess(listener) == allowed) return;
            Thread.sleep(50);
        }
        throw new AssertionError("notification listener access did not reach allowed=" + allowed);
    }

    private String shell(String command) throws IOException {
        ParcelFileDescriptor output = getUiAutomation().executeShellCommand(command);
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(output);
             ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int size;
            while ((size = input.read(buffer)) >= 0) result.write(buffer, 0, size);
            return result.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String probePostureSamples() throws Exception {
        FaceDetector detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build());
        StringBuilder report = new StringBuilder();
        try {
            for (String name : new String[]{"neutral", "chin-down"}) {
                File file = new File(getTargetContext().getFilesDir(), "posture-" + name + ".png");
                Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                require(bitmap != null, "missing adult posture fixture " + name);
                try {
                    var faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)),
                            15, TimeUnit.SECONDS);
                    require(faces.size() == 1, "expected one face in adult posture fixture " + name);
                    Face face = faces.get(0);
                    report.append(String.format(Locale.ROOT, "POSE_SAMPLE %s pitch=%.2f roll=%.2f yaw=%.2f%n",
                            name, face.getHeadEulerAngleX(), face.getHeadEulerAngleZ(), face.getHeadEulerAngleY()));
                } finally {
                    bitmap.recycle();
                }
            }
        } finally {
            detector.close();
        }
        return report.toString();
    }

    private void testTransport(Pairing pairing) throws Exception {
        byte[] wrong = pairing.fingerprint.clone();
        wrong[0] ^= 1;
        AtomicBoolean rejectedPin = new AtomicBoolean();
        try {
            request("GET", "/state", pairing.token, null, socketFactory(wrong, () -> rejectedPin.set(true)));
            throw new AssertionError("wrong pin accepted");
        } catch (SSLHandshakeException expected) {
            check(rejectedPin.get(), "wrong pin rejected during handshake");
        }

        try (Socket plaintext = new Socket()) {
            plaintext.connect(new InetSocketAddress("127.0.0.1", 8787), 1500);
            plaintext.setSoTimeout(2000);
            int firstByte;
            try {
                plaintext.getOutputStream().write(
                        "GET /state HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                firstByte = plaintext.getInputStream().read();
            } catch (SocketException rejected) {
                firstByte = -1;
            }
            check(firstByte == -1 || firstByte == 21, "TLS server rejects raw plaintext HTTP");
        }
        check(request("GET", "/state", pairing.token, null).status == 200,
                "valid pin and bearer still work after rejection");
    }

    private void testCameraPreview(String token) throws Exception {
        awaitState(token, "cameraReady", "true", 18000);
        check(true, "CameraX frame completes bundled ML Kit analysis");
        check(request("GET", "/preview.jpg", token, null).status == 404,
                "running camera still requires preview opt-in");
        setSwitch("차량에 카메라 미리보기 공유", true);
        awaitState(token, "previewShared", "true", 2000);
        long deadline = SystemClock.elapsedRealtime() + 8000;
        Reply frame;
        do {
            frame = request("GET", "/preview.jpg", token, null);
            if (frame.status == 200) break;
            Thread.sleep(150);
        } while (SystemClock.elapsedRealtime() < deadline);
        check(frame.status == 200 && frame.jpeg, "opt-in exposes one authenticated camera JPEG");
        setSwitch("차량에 카메라 미리보기 공유", false);
        check(request("GET", "/preview.jpg", token, null).status == 404,
                "withdrawal clears preview without waiting for expiry");
        awaitState(token, "previewShared", "false", 2000);
    }

    private void testAutoQuietInterruption(String token) throws Exception {
        File fixture = new File(autoQuietVideo);
        require(fixture.isFile() && fixture.canRead(), "auto-quiet video fixture is unreadable");
        require(!mediaState(token).getBoolean("available"), "test must start without selected media");
        Field videoRequest = CompanionActivity.class.getDeclaredField("VIDEO_REQUEST");
        videoRequest.setAccessible(true);
        int requestCode = videoRequest.getInt(null);
        Intent selected = new Intent().setData(Uri.fromFile(fixture));
        runOnMainSync(() -> activity.onActivityResult(requestCode, Activity.RESULT_OK, selected));
        awaitMediaFlag(token, "available", true);
        check(true, "local video prepared from the document picker result");

        setSwitch("시연 입력 사용", true);
        wakeScenario(token);
        commandMedia(token, "RESUME");
        checkMedia(awaitMediaFlag(token, "playing", true), true, .85, 1,
                "local video starts at its original levels");
        commandMedia(token, "PAUSE");
        awaitMediaFlag(token, "playing", false);

        startObservedRamp(token);
        runOnMainSync(() -> click("얼굴 가림"));
        JSONObject unavailable = awaitState(token, "sleepState", "UNKNOWN", 3000);
        checkMedia(unavailable.getJSONObject("media"), false, .85, 1,
                "observation loss aborts the ramp and preserves the paused video");
        Thread.sleep(2200);
        JSONObject afterGap = new JSONObject(request("GET", "/state", token, null).body);
        check("UNKNOWN".equals(afterGap.getString("sleepState"))
                        && afterGap.getLong("totalSleepMs") == unavailable.getLong("totalSleepMs"),
                "the observation gap does not add sleep time");
        checkMedia(afterGap.getJSONObject("media"), false, .85, 1,
                "no deferred dimming or autoplay during the observation gap");
        wakeScenario(token);
        checkMedia(mediaState(token), false, .85, 1,
                "restored observation does not resume the cancelled transition");

        commandMedia(token, "RESUME");
        awaitMediaFlag(token, "playing", true);
        startObservedRamp(token);
        checkMedia(commandMedia(token, "PAUSE"), false, .85, 1,
                "pause cancels the ramp and restores the original levels");
        Thread.sleep(2200);
        checkMedia(mediaState(token), false, .85, 1,
                "pause and original levels persist beyond the ramp");

        wakeScenario(token);
        commandMedia(token, "RESUME");
        awaitMediaFlag(token, "playing", true);
        startObservedRamp(token);
        checkMedia(commandMedia(token, "DIM"), true, .18, 1,
                "manual dim changes only brightness during the ramp");
        Thread.sleep(2200);
        checkMedia(mediaState(token), true, .18, 1,
                "manual dim keeps the original audio level beyond the ramp");

        wakeScenario(token);
        commandMedia(token, "RESUME");
        awaitMediaFlag(token, "playing", true);
        commandMedia(token, "PAUSE");
        awaitMediaFlag(token, "playing", false);
        startObservedRamp(token);
        commandMedia(token, "RESUME");
        checkMedia(awaitMediaFlag(token, "playing", true), true, .85, 1,
                "resume during the ramp starts the video at its original levels");
        Thread.sleep(2200);
        checkMedia(mediaState(token), true, .85, 1,
                "resume keeps the original levels beyond the ramp");
    }

    private void wakeScenario(String token) throws Exception {
        runOnMainSync(() -> click("눈 뜸"));
        awaitState(token, "sleepState", "AWAKE", 6000);
    }

    private void startObservedRamp(String token) throws Exception {
        runOnMainSync(() -> click("눈 감김"));
        long deadline = SystemClock.elapsedRealtime() + 12000;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONObject state = new JSONObject(request("GET", "/state", token, null).body);
            if ("ASLEEP".equals(state.getString("sleepState"))) {
                JSONObject media = state.getJSONObject("media");
                double gain = media.getDouble("volume");
                if (!media.getBoolean("quiet") && gain > .6 && gain < .98
                        && media.getDouble("brightness") < .84) {
                    check(true, "observed a partially applied automatic transition");
                    return;
                }
                require(!media.getBoolean("quiet") && gain > .6, "missed the start of the automatic transition");
            }
            Thread.sleep(75);
        }
        throw new AssertionError("automatic transition did not start");
    }

    private JSONObject awaitMediaFlag(String token, String key, boolean value) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 6000;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONObject media = mediaState(token);
            if (media.getBoolean(key) == value) return media;
            Thread.sleep(100);
        }
        throw new AssertionError("media did not reach " + key + "=" + value);
    }

    private JSONObject mediaState(String token) throws Exception {
        Reply reply = request("GET", "/state", token, null);
        require(reply.status == 200, "media state request failed: " + reply.status);
        return new JSONObject(reply.body).getJSONObject("media");
    }

    private JSONObject commandMedia(String token, String action) throws Exception {
        Reply reply = request("POST", "/command", token, "{\"action\":\"" + action + "\"}");
        require(reply.status == 200, action + " request failed: " + reply.status);
        JSONObject response = new JSONObject(reply.body);
        require(response.getBoolean("ok"), action + " command failed: " + response.optString("error"));
        return response.getJSONObject("state").getJSONObject("media");
    }

    private void checkMedia(JSONObject media, boolean playing, double brightness, double volume, String message)
            throws Exception {
        check(media.getBoolean("playing") == playing && !media.getBoolean("quiet")
                        && Math.abs(media.getDouble("brightness") - brightness) < .01
                        && Math.abs(media.getDouble("volume") - volume) < .01,
                message + " (playing=" + media.getBoolean("playing") + ", quiet=" + media.getBoolean("quiet")
                        + ", brightness=" + media.getDouble("brightness") + ", volume=" + media.getDouble("volume") + ")");
    }

    private void testTripRecords(String token) throws Exception {
        TripSleepStore store = new TripSleepStore(getTargetContext());
        int priorCount = store.history().size();
        runOnMainSync(() -> click("이동 기록 시작"));
        Thread.sleep(800);
        runOnMainSync(() -> click("이동 기록 종료"));
        java.util.List<Trip> trips = new TripSleepStore(getTargetContext()).history();
        check(trips.size() == priorCount + 1 && trips.get(0).durationMs() >= 700,
                "completed real-camera-mode trip is persisted");
        check(trips.get(0).sleep().isEmpty(), "awake camera input does not invent sleep");
        check(!new JSONObject(request("GET", "/state", token, null).body).has("trips"),
                "trip history is private to the phone");
        check(store.delete(trips.get(0).id()), "completed trip can be removed");

        String id = UUID.randomUUID().toString();
        Trip fixture = new Trip(id, 1000, 15_000, false,
                Arrays.asList(new Span(1000, 4000), new Span(8000, 10_000)),
                Collections.singletonList(new Span(5000, 7000)));
        check(store.checkpoint(fixture) && store.recoverInterrupted(), "interrupted trip is recovered");
        Trip recovered = store.history().stream().filter(trip -> trip.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("missing interrupted trip"));
        check(recovered.interrupted() && recovered.totalSleepMs() == 5000 && recovered.gaps().size() == 1,
                "reload keeps supported sleep and observation gaps");
        check(recovered.corrected(0, new Span(2000, 6000)) == null, "correction cannot cover a camera gap");
        Trip corrected = recovered.corrected(0, new Span(2000, 4000));
        check(corrected != null && corrected.totalSleepMs() == 4000 && store.saveCorrection(corrected),
                "valid correction changes observed total and persists");
        Trip reloaded = new TripSleepStore(getTargetContext()).history().stream()
                .filter(trip -> trip.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("missing corrected trip"));
        check(reloaded.totalSleepMs() == 4000 && store.delete(id), "correction survives reload and trip can be deleted");
    }

    private void setSwitch(String text, boolean checked) {
        AtomicReference<Boolean> changed = new AtomicReference<>(false);
        runOnMainSync(() -> {
            View view = findText(content(), text);
            if (view instanceof CompoundButton && view.isEnabled()) {
                ((CompoundButton) view).setChecked(checked);
                changed.set(true);
            }
        });
        require(changed.get(), "switch unavailable: " + text);
    }

    private void testCommandTimeout(String token) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            entered.countDown();
            try {
                release.await(4, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        require(entered.await(1, TimeUnit.SECONDS), "UI timeout fixture started");
        try {
            Reply reply = request("POST", "/command", token, "{\"action\":\"RESET\"}");
            check(reply.status == 503, "blocked UI command returns bounded timeout");
        } finally {
            release.countDown();
        }
        waitForIdleSync();
        JSONObject current = new JSONObject(request("GET", "/state", token, null).body);
        check(current.getJSONArray("alerts").length() > 0,
                "timed out reset cannot execute later and erase alerts");
    }

    private JSONObject awaitState(String token, String key, String value, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONObject state = new JSONObject(request("GET", "/state", token, null).body);
            if (value.equals(state.optString(key))) return state;
            Thread.sleep(100);
        }
        throw new AssertionError("state did not reach " + key + "=" + value);
    }

    private void awaitAlert(String token, String kind, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            org.json.JSONArray alerts = new JSONObject(request("GET", "/state", token, null).body)
                    .getJSONArray("alerts");
            for (int index = 0; index < alerts.length(); index++) {
                if (kind.equals(alerts.getJSONObject(index).getString("kind"))) return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("alert not observed: " + kind);
    }

    private Pairing awaitPairingCode() throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + 7000;
        AtomicReference<Pairing> value = new AtomicReference<>();
        do {
            runOnMainSync(() -> value.set(findPairingCode(content())));
            if (value.get() != null) return value.get();
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("pairing code not displayed");
    }

    private Pairing findPairingCode(View view) {
        if (view instanceof TextView) {
            Matcher match = PAIRING_CODE.matcher(((TextView) view).getText());
            if (match.find()) return new Pairing(Base64.getUrlDecoder().decode(match.group(1)), match.group(2));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                Pairing found = findPairingCode(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void click(String text) {
        View view = findText(content(), text);
        require(view != null, "missing control: " + text);
        view.performClick();
    }

    private View content() { return activity.findViewById(android.R.id.content); }

    private View findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findText(group.getChildAt(index), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void check(boolean condition, String message) {
        require(condition, message);
        passed++;
        Bundle update = new Bundle();
        update.putString("stream", "PASS " + message + "\n");
        sendStatus(0, update);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private Reply request(String method, String path, String token, String body) throws IOException {
        return request(method, path, token, body, pinnedSocketFactory);
    }

    private Reply request(String method, String path, String token, String body,
                          SSLSocketFactory socketFactory) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL("https://127.0.0.1:8787" + path)
                .openConnection(Proxy.NO_PROXY);
        connection.setSSLSocketFactory(socketFactory);
        connection.setHostnameVerifier((host, session) -> "127.0.0.1".equals(host)
                && ("TLSv1.2".equals(session.getProtocol()) || "TLSv1.3".equals(session.getProtocol())));
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(3500);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Connection", "close");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = "";
            boolean jpeg = false;
            if (stream != null) {
                try (InputStream input = stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[1024];
                    int size;
                    while ((size = input.read(buffer)) >= 0) bytes.write(buffer, 0, size);
                    if ("image/jpeg".equals(connection.getContentType())) {
                        byte[] frame = bytes.toByteArray();
                        jpeg = frame.length > 2 && (frame[0] & 255) == 255 && (frame[1] & 255) == 216;
                    } else {
                        text = bytes.toString(StandardCharsets.UTF_8.name());
                    }
                }
            }
            return new Reply(status, text, jpeg);
        } finally {
            connection.disconnect();
        }
    }

    private static SSLSocketFactory socketFactory(byte[] fingerprint, Runnable rejectedPin)
            throws GeneralSecurityException {
        X509TrustManager trust = new X509TrustManager() {
            @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Missing peer certificate");
                chain[0].checkValidity();
                try {
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    if (!MessageDigest.isEqual(fingerprint, digest)) {
                        rejectedPin.run();
                        throw new CertificateException("Fingerprint mismatch");
                    }
                } catch (java.security.NoSuchAlgorithmException unavailable) {
                    throw new CertificateException(unavailable);
                }
            }

            @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                throw new CertificateException("Client certificate not expected");
            }

            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trust}, null);
        return context.getSocketFactory();
    }

    private record Pairing(byte[] fingerprint, String token) {}

    private static final class Reply {
        final int status;
        final String body;
        final boolean jpeg;
        Reply(int status, String body, boolean jpeg) {
            this.status = status;
            this.body = body;
            this.jpeg = jpeg;
        }
    }
}
