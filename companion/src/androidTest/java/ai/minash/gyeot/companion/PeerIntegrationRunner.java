package ai.minash.gyeot.companion;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PeerIntegrationRunner extends Instrumentation {
    private CompanionActivity activity;
    private int passed;
    private boolean cameraFixture;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        cameraFixture = arguments != null && Boolean.parseBoolean(arguments.getString("cameraFixture"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Intent intent = new Intent(getTargetContext(), CompanionActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (CompanionActivity) startActivitySync(intent);
            waitForIdleSync();
            String token = tokenFromScreen();
            require(token != null, "session token displayed");

            check(request("GET", "/state", null, null).status == 401, "state rejects missing token");
            check(request("GET", "/state", "invalid", null).status == 401, "state rejects wrong token");
            JSONObject initial = awaitState(token, "connected", "true", 3000);
            check("camera".equals(initial.getString("source")),
                    "authenticated state positive control");
            check(request("GET", "/preview.jpg", null, null).status == 401,
                    "preview requires authentication");
            check(request("GET", "/preview.jpg", token, null).status == 404,
                    "authenticated preview is off by default");
            if (cameraFixture) testCameraPreview(token);
            check(request("GET", "/missing", token, null).status == 404, "unknown route rejected");
            check(request("POST", "/command", token, "{bad").status == 400, "malformed command rejected");
            check(request("POST", "/command", token, "{\"action\":\"ERASE\"}").status == 400,
                    "unknown action rejected");
            char[] oversized = new char[5000];
            java.util.Arrays.fill(oversized, 'x');
            check(request("POST", "/command", token, new String(oversized)).status == 400,
                    "oversized command rejected");
            Reply quiet = request("POST", "/command", token, "{\"action\":\"QUIET\"}");
            JSONObject quietBody = new JSONObject(quiet.body);
            check(quiet.status == 200 && quietBody.getBoolean("ok")
                            && quietBody.getJSONObject("state").getJSONObject("media").getBoolean("quiet"),
                    "quiet command changes actual foreground app state");
            Reply resume = request("POST", "/command", token, "{\"action\":\"RESUME\"}");
            check(resume.status == 200 && !new JSONObject(resume.body).getBoolean("ok"),
                    "resume without selected video reports failure");

            setSwitch("시연 입력 사용", true);
            JSONObject scenario = awaitState(token, "source", "scenario", 3000);
            check(!scenario.getBoolean("cameraVisible"), "scenario never claims camera observation");
            runOnMainSync(() -> click("눈 감김"));
            JSONObject sleeping = awaitState(token, "sleepState", "ASLEEP", 12000);
            check(sleeping.getLong("totalSleepMs") >= 8000, "sustained scenario closes eyes into sleep estimate");
            runOnMainSync(() -> click("눈 뜸"));
            awaitState(token, "sleepState", "AWAKE", 5000);
            check(!new JSONObject(request("GET", "/state", token, null).body)
                    .getJSONObject("media").getBoolean("playing"), "wake never starts media automatically");
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
                check(true, "pause closes HTTP endpoint");
            }
            runOnMainSync(() -> callActivityOnResume(activity));
            String newToken = tokenFromScreen();
            check(newToken != null && !newToken.equals(token), "resume rotates pairing token");
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

    private void testCameraPreview(String token) throws Exception {
        awaitState(token, "cameraReady", "true", 18000);
        check(true, "CameraX frame completes bundled ML Kit analysis");
        check(request("GET", "/preview.jpg", token, null).status == 404,
                "running camera still requires preview opt-in");
        setSwitch("차량에 카메라 미리보기 공유", true);
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

    private String tokenFromScreen() {
        AtomicReference<String> value = new AtomicReference<>();
        runOnMainSync(() -> value.set(findToken(content())));
        return value.get();
    }

    private String findToken(View view) {
        if (view instanceof TextView) {
            Matcher match = Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                    .matcher(((TextView) view).getText());
            if (match.find()) return match.group();
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                String found = findToken(group.getChildAt(index));
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
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:8787" + path).openConnection();
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(3500);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Connection", "close");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.getOutputStream().write(payload);
            connection.getOutputStream().close();
        }
        try {
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
