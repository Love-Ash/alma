package ai.minash.gyeot.vehicle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONException;
import org.json.JSONObject;

final class CompanionClient {
    static final String KIDS_PAUSE = "KIDS_PAUSE";
    static final String KIDS_RESUME = "KIDS_RESUME";
    private static final Pattern PAIRING_CODE = Pattern.compile("g1\\.([A-Za-z0-9_-]{43})\\.([A-Za-z0-9_-]{22})");
    private static final int READ_TIMEOUT_MS = 2000;
    private static final int KIDS_READ_TIMEOUT_MS = 4000;
    private final String host;
    private final String origin;
    private final String token;
    private final PinnedPeer peer;

    CompanionClient(String address, String pairingCode) {
        URI parsed = URI.create("https://" + address.trim());
        String name = parsed.getHost();
        if (name == null || parsed.getUserInfo() != null || parsed.getQuery() != null
                || parsed.getFragment() != null || !(parsed.getPath().isEmpty())
                || !isLocalHost(name) || (parsed.getPort() != -1 && parsed.getPort() != 8787)) {
            throw new IllegalArgumentException("같은 네트워크의 휴대폰 IPv4 주소를 입력해 주세요");
        }
        Matcher code = PAIRING_CODE.matcher(pairingCode.trim());
        if (!code.matches()) {
            throw new IllegalArgumentException("휴대폰에 표시된 연결 키를 입력해 주세요");
        }
        host = name;
        origin = "https://" + name + ":8787";
        token = code.group(2);
        try {
            peer = new PinnedPeer(Base64.getUrlDecoder().decode(code.group(1)));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalArgumentException("안전한 연결을 준비하지 못했어요", unavailable);
        }
    }

    private static boolean isLocalHost(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) return false;
        int[] bytes = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!octets[i].matches("0|[1-9][0-9]{0,2}")) return false;
            bytes[i] = Integer.parseInt(octets[i]);
            if (bytes[i] > 255) return false;
        }
        return bytes[0] == 10 || bytes[0] == 127 || (bytes[0] == 192 && bytes[1] == 168)
                || (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31);
    }

    JSONObject state() throws IOException, JSONException {
        return new JSONObject(new String(request("/state", null, READ_TIMEOUT_MS), StandardCharsets.UTF_8));
    }

    void command(String action) throws IOException {
        try {
            JSONObject result = requestCommand(action);
            if (isKidsPlaybackAction(action)) {
                if (Boolean.FALSE.equals(result.opt("ok"))) {
                    throw new KidsPlaybackRejected(result.optString("error"));
                }
                JSONObject playback = result.optJSONObject("kidsPlayback");
                boolean expectedPlaying = KIDS_RESUME.equals(action);
                if (!Boolean.TRUE.equals(result.opt("ok")) || playback == null
                        || !Boolean.valueOf(expectedPlaying).equals(playback.opt("playing"))) {
                    throw new IOException("유튜브 키즈 재생 상태를 확인하지 못했어요");
                }
                return;
            }
            if (!result.optBoolean("ok")) throw new IOException("휴대폰에서 요청을 완료하지 못했어요");
            JSONObject state = result.getJSONObject("state");
            if (!state.getBoolean("connected") || !commandApplied(action, state.getJSONObject("media"))) {
                throw new IOException("휴대폰에서 요청 결과를 확인하지 못했어요");
            }
        } catch (JSONException e) { throw new IOException("휴대폰 명령 응답을 확인할 수 없어요", e); }
    }

    static boolean isKidsPlaybackAction(String action) {
        return KIDS_PAUSE.equals(action) || KIDS_RESUME.equals(action);
    }

    long requestScreenLock() throws IOException {
        try {
            JSONObject result = requestCommand("LOCK_SCREEN");
            if (!result.optBoolean("ok")) throw new ScreenLockRejected(result.optString("error"));
            long baseline = cameraFrameSequence(result.getJSONObject("state"));
            if (baseline < 0) throw new IOException("잠금 요청 이후 카메라 상태를 확인할 수 없어요");
            return baseline;
        } catch (JSONException e) { throw new IOException("휴대폰 명령 응답을 확인할 수 없어요", e); }
    }

    static long cameraFrameSequence(JSONObject state) {
        Object sequence = state.opt("cameraFrameSequence");
        if (sequence instanceof Integer value) return value.longValue();
        if (sequence instanceof Long value) return value;
        return -1;
    }

    private JSONObject requestCommand(String action) throws IOException, JSONException {
        int timeout = isKidsPlaybackAction(action) ? KIDS_READ_TIMEOUT_MS : READ_TIMEOUT_MS;
        byte[] body = request("/command", ("{\"action\":\"" + action + "\"}").getBytes(StandardCharsets.UTF_8), timeout);
        return new JSONObject(new String(body, StandardCharsets.UTF_8));
    }

    static final class KidsPlaybackRejected extends IOException {
        final String reason;

        KidsPlaybackRejected(String reason) {
            super("휴대폰에서 유튜브 키즈 재생 요청을 거부했어요");
            this.reason = reason;
        }
    }

    static final class ScreenLockRejected extends IOException {
        final String reason;

        ScreenLockRejected(String reason) {
            super("휴대폰에서 잠금 요청을 거부했어요");
            this.reason = reason;
        }
    }

    private static boolean commandApplied(String action, JSONObject media) throws JSONException {
        return switch (action) {
            case "PAUSE" -> media.getBoolean("available") && !media.getBoolean("playing");
            case "RESUME" -> media.getBoolean("playing") && !media.getBoolean("quiet");
            case "QUIET" -> media.getBoolean("quiet") && isDimmed(media);
            case "DIM" -> isDimmed(media);
            default -> false;
        };
    }

    private static boolean isDimmed(JSONObject media) throws JSONException {
        return Math.abs(media.getDouble("brightness") - 0.18) < 0.001;
    }

    byte[] preview() throws IOException { return request("/preview.jpg", null, READ_TIMEOUT_MS); }

    private byte[] request(String path, byte[] body, int readTimeoutMs) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(origin + path).toURL().openConnection(Proxy.NO_PROXY);
        connection.setSSLSocketFactory(peer.socketFactory);
        connection.setHostnameVerifier((name, session) -> host.equals(name) && peer.matches(session));
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
            }
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException(status == 401 ? "연결 키를 확인해 주세요" : "휴대폰 응답 오류 " + status);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (output.size() + read > 2_000_000) throw new IOException("휴대폰 응답 크기 초과");
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } finally { connection.disconnect(); }
    }
}
