package ai.minash.gyeot.vehicle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

final class CompanionClient {
    private final String origin;
    private final String token;

    CompanionClient(String host, String token) {
        URI parsed = URI.create("http://" + host.trim());
        String name = parsed.getHost();
        if (name == null || parsed.getUserInfo() != null || parsed.getQuery() != null
                || parsed.getFragment() != null || !(parsed.getPath().isEmpty())
                || !isLocalHost(name) || (parsed.getPort() != -1 && parsed.getPort() != 8787)) {
            throw new IllegalArgumentException("같은 네트워크의 휴대폰 IPv4 주소를 입력해 주세요");
        }
        if (!token.matches("[A-Za-z0-9_-]{20,128}")) {
            throw new IllegalArgumentException("휴대폰에 표시된 연결 키를 입력해 주세요");
        }
        origin = "http://" + name + ":8787";
        this.token = token;
    }

    private static boolean isLocalHost(String host) {
        if (host.equals("localhost")) return true;
        String[] octets = host.split("\\.");
        if (octets.length != 4) return false;
        int[] bytes = new int[4];
        for (int i = 0; i < 4; i++) {
            try { bytes[i] = Integer.parseInt(octets[i]); }
            catch (NumberFormatException e) { return false; }
            if (bytes[i] < 0 || bytes[i] > 255) return false;
        }
        return bytes[0] == 10 || bytes[0] == 127 || (bytes[0] == 192 && bytes[1] == 168)
                || (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31);
    }

    JSONObject state() throws IOException, JSONException {
        return new JSONObject(new String(request("/state", null), StandardCharsets.UTF_8));
    }

    void command(String action) throws IOException {
        byte[] body = request("/command", ("{\"action\":\"" + action + "\"}").getBytes(StandardCharsets.UTF_8));
        try {
            JSONObject result = new JSONObject(new String(body, StandardCharsets.UTF_8));
            if (!result.optBoolean("ok", false)) throw new IOException("휴대폰에서 요청을 완료하지 못했어요");
        } catch (JSONException e) { throw new IOException("휴대폰 명령 응답을 확인할 수 없어요", e); }
    }

    byte[] preview() throws IOException { return request("/preview.jpg", null); }

    private byte[] request(String path, byte[] body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(origin + path).toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
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
