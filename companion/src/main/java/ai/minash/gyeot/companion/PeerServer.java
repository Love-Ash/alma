package ai.minash.gyeot.companion;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Function;
import java.util.function.Supplier;

final class PeerServer extends NanoHTTPD {
    private static final int MAX_BODY = 4096;
    private final byte[] authorization;
    private final Supplier<String> state;
    private final Supplier<byte[]> preview;
    private final Function<String, String> command;

    PeerServer(String token, Supplier<String> state, Supplier<byte[]> preview,
               Function<String, String> command) {
        super(8787);
        authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        this.state = state;
        this.preview = preview;
        this.command = command;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String supplied = session.getHeaders().getOrDefault("authorization", "");
        if (!MessageDigest.isEqual(authorization, supplied.getBytes(StandardCharsets.UTF_8))) {
            return error(Response.Status.UNAUTHORIZED, "pairing_required");
        }
        if (!isLocal(session.getRemoteIpAddress())) {
            return error(Response.Status.FORBIDDEN, "local_network_only");
        }
        if (session.getMethod() == Method.GET && "/state".equals(session.getUri())) {
            return json(Response.Status.OK, state.get());
        }
        if (session.getMethod() == Method.GET && "/preview.jpg".equals(session.getUri())) {
            byte[] bytes = preview.get();
            if (bytes == null) return error(Response.Status.NOT_FOUND, "preview_unavailable");
            Response response = newFixedLengthResponse(Response.Status.OK, "image/jpeg",
                    new ByteArrayInputStream(bytes), bytes.length);
            response.addHeader("Cache-Control", "no-store");
            response.closeConnection(true);
            return response;
        }
        if (!"/command".equals(session.getUri())) {
            return error(Response.Status.NOT_FOUND, "route_not_found");
        }
        if (session.getMethod() != Method.POST) {
            return error(Response.Status.METHOD_NOT_ALLOWED, "post_required");
        }
        if (session.getHeaders().containsKey("transfer-encoding")) {
            return error(Response.Status.BAD_REQUEST, "content_length_required");
        }
        String type = session.getHeaders().getOrDefault("content-type", "");
        if (!type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            return error(Response.Status.BAD_REQUEST, "json_required");
        }
        try {
            int length = Integer.parseInt(session.getHeaders().getOrDefault("content-length", "0"));
            if (length < 1 || length > MAX_BODY) {
                return error(Response.Status.BAD_REQUEST, "invalid_body_size");
            }
            byte[] bytes = new byte[length];
            int offset = 0;
            while (offset < length) {
                int read = session.getInputStream().read(bytes, offset, length - offset);
                if (read < 0) return error(Response.Status.BAD_REQUEST, "incomplete_body");
                offset += read;
            }
            JSONObject request = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            String action = request.getString("action");
            if (!action.equals("QUIET") && !action.equals("PAUSE")
                    && !action.equals("RESUME") && !action.equals("RESET")) {
                return error(Response.Status.BAD_REQUEST, "unknown_action");
            }
            String result = command.apply(action);
            if (result == null) return error(Response.Status.SERVICE_UNAVAILABLE, "command_unavailable");
            return json(Response.Status.OK, result);
        } catch (NumberFormatException | JSONException malformed) {
            return error(Response.Status.BAD_REQUEST, "invalid_command");
        } catch (IOException failedRead) {
            return error(Response.Status.BAD_REQUEST, "body_read_failed");
        }
    }

    private static boolean isLocal(String address) {
        try {
            InetAddress ip = InetAddress.getByName(address);
            return ip.isLoopbackAddress() || ip.isSiteLocalAddress() || ip.isLinkLocalAddress();
        } catch (IOException invalidAddress) {
            return false;
        }
    }

    private static Response error(Response.Status status, String reason) {
        return json(status, "{\"error\":\"" + reason + "\"}");
    }

    private static Response json(Response.Status status, String body) {
        Response response = newFixedLengthResponse(status, "application/json; charset=utf-8", body);
        response.addHeader("Cache-Control", "no-store");
        response.closeConnection(true);
        return response;
    }
}
