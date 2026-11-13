/*
 * Copyright 2026 wchill.
 * https://github.com/wchill/patcheddit
 *
 * See the included NOTICE file for GPLv3 section 7 terms.
 */

package app.morphe.extension.shared.fixes.gallery;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import app.morphe.extension.shared.requests.PatchedditInterceptor;
import app.morphe.extension.shared.requests.RedditRouteResponseShim;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Since late October 2026 Reddit's public API returns every gallery item in
 * {@code media_metadata} as {@code {"status": "failed", "id": "..."}} with no
 * URLs, type or dimensions, although the images are still served from
 * {@code i.redd.it}. Clients drop items that aren't {@code "valid"}, so
 * galleries fail to load. This rebuilds failed items as valid image entries
 * pointing at {@code https://i.redd.it/<id>.<ext>}.
 */
public class GalleryMetadataResponsePatch extends PatchedditInterceptor {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final Pattern FAILED = Pattern.compile("\"status\"\\s*:\\s*\"failed\"");
    private static final Pattern EXTENSION =
            Pattern.compile("\\.(jpe?g|png|gif|webp)(?:[?#]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEDIA_ID = Pattern.compile("[A-Za-z0-9]{1,32}");
    // Reddit sends no dimensions for failed items; clients only need a plausible aspect ratio.
    static final int PLACEHOLDER_SIZE = 1080;

    @Override
    public boolean isPatchIncluded() {
        // Overridden by patch.
        return false;
    }

    @NonNull
    @Override
    protected Response doIntercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        Response response = chain.proceed(request);
        if (!request.method().equals("GET")
                || !RedditRouteResponseShim.isRedditHost(request.url().host())
                || !response.isSuccessful()) {
            return response;
        }
        ResponseBody body = response.body();
        if (body == null) return response;
        MediaType type = body.contentType();
        if (type == null || !type.toString().toLowerCase(Locale.ROOT).contains("json")) {
            return response;
        }
        String encoding = response.header("Content-Encoding");
        boolean gzip = encoding != null && encoding.equalsIgnoreCase("gzip");
        if (encoding != null && !gzip && !encoding.equalsIgnoreCase("identity")) return response;
        if (body.contentLength() > MAX_BYTES) return response;

        byte[] raw;
        try (ResponseBody peek = response.peekBody(MAX_BYTES + 1)) {
            if (peek.contentLength() > MAX_BYTES) return response;
            raw = peek.bytes();
        }
        String text = gzip ? gunzip(raw) : new String(raw, StandardCharsets.UTF_8);
        if (text == null || !text.contains("media_metadata") || !FAILED.matcher(text).find()) {
            return response;
        }

        String repaired;
        try {
            Object json = new JSONTokener(text).nextValue();
            if (!repair(json)) return response;
            repaired = json.toString();
        } catch (JSONException exception) {
            // Leave anything we can't parse untouched.
            return response;
        }
        response.close();
        return response.newBuilder()
                .removeHeader("Content-Length")
                .removeHeader("Content-Encoding")
                .removeHeader("ETag")
                .body(ResponseBody.create(repaired, JSON))
                .build();
    }

    /** Walks any Reddit JSON shape and repairs every media_metadata map found. */
    static boolean repair(Object node) throws JSONException {
        boolean changed = false;
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) changed |= repair(array.opt(i));
        } else if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject metadata = object.optJSONObject("media_metadata");
            if (metadata != null) changed |= repairMetadata(metadata, extensionHint(object));
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.equals("media_metadata")) continue;
                Object child = object.opt(key);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    changed |= repair(child);
                }
            }
        }
        return changed;
    }

    private static boolean repairMetadata(JSONObject metadata, String extension)
            throws JSONException {
        boolean changed = false;
        Iterator<String> keys = metadata.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject item = metadata.optJSONObject(key);
            if (item == null || !"failed".equals(item.optString("status"))) continue;
            if (item.has("s")) continue; // Reddit gave us something usable; leave it.
            String id = item.optString("id", key);
            if (!MEDIA_ID.matcher(id).matches()) continue;
            metadata.put(key, validImage(id, extension));
            changed = true;
        }
        return changed;
    }

    static JSONObject validImage(String id, String extension) throws JSONException {
        String url = "https://i.redd.it/" + id + "." + extension;
        JSONObject source = new JSONObject()
                .put("u", url)
                .put("x", PLACEHOLDER_SIZE)
                .put("y", PLACEHOLDER_SIZE);
        JSONObject preview = new JSONObject(source.toString());
        return new JSONObject()
                .put("status", "valid")
                .put("e", "Image")
                .put("m", "image/" + (extension.equals("jpeg") ? "jpg" : extension))
                .put("id", id)
                .put("p", new JSONArray().put(preview))
                .put("s", source);
    }

    /** Guesses the file extension from the post's thumbnail, as Reddit sends none. */
    static String extensionHint(JSONObject post) {
        Matcher matcher = EXTENSION.matcher(post.optString("thumbnail", ""));
        if (!matcher.find()) return "jpeg";
        String extension = matcher.group(1).toLowerCase(Locale.ROOT);
        return extension.equals("jpg") ? "jpeg" : extension;
    }

    private static String gunzip(byte[] raw) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_BYTES) return null;
            }
            return out.toString("UTF-8");
        } catch (IOException exception) {
            return null;
        }
    }
}
