package kr.co.iefriends.pcsx2;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Compatibility bridge expected by ARMSX2's Android HTTP downloader. */
public final class HttpClient {
    private HttpClient() {}

    public static final class Response {
        public int statusCode;
        public String contentType = "";
        public byte[] data = new byte[0];
    }

    public static Response doRequest(String url, String method, byte[] postData, String userAgent, int timeoutMs) {
        Response out = new Response();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            if (userAgent != null && !userAgent.isEmpty()) conn.setRequestProperty("User-Agent", userAgent);
            if ("POST".equalsIgnoreCase(method) && postData != null) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(postData.length);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream os = conn.getOutputStream()) { os.write(postData); }
            }
            out.statusCode = conn.getResponseCode();
            String type = conn.getContentType();
            out.contentType = type == null ? "" : type;
            InputStream input;
            try { input = conn.getInputStream(); } catch (Throwable ignored) { input = conn.getErrorStream(); }
            if (input != null) {
                try (InputStream in = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) > 0) bytes.write(buffer, 0, count);
                    out.data = bytes.toByteArray();
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            out.statusCode = -2;
        } catch (Throwable e) {
            out.statusCode = -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }
}
