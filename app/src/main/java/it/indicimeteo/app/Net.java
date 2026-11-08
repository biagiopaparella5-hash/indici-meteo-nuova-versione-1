package it.indicimeteo.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Download semplice con redirect (anche http -> https) e User-Agent. */
final class Net {
    static final String UA = "Mozilla/5.0 (Linux; Android) IndiciMeteo/1.0";

    static final class Result {
        int code;
        String contentType;
        String lastModified;
        String etag;
        long length = -1;
        byte[] body = new byte[0];
    }

    static Result get(String url, String range) throws IOException {
        return request(url, "GET", range);
    }

    static Result head(String url) throws IOException {
        return request(url, "HEAD", null);
    }

    private static Result request(String url, String method, String range) throws IOException {
        String current = url;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            if (range != null) {
                c.setRequestProperty("Range", range);
                c.setRequestProperty("Accept-Encoding", "identity"); // niente gzip sui pezzi di file
            }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                current = new URL(new URL(current), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            Result r = new Result();
            r.code = code;
            r.contentType = c.getContentType();
            r.lastModified = c.getHeaderField("Last-Modified");
            r.etag = c.getHeaderField("ETag");
            r.length = c.getContentLengthLong();
            if (!"HEAD".equals(method)) {
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in != null) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close();
                    r.body = out.toByteArray();
                }
            }
            c.disconnect();
            return r;
        }
        throw new IOException("Troppi redirect");
    }

    private Net() { }
}
