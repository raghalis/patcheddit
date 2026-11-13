/*
 * Copyright 2026 wchill.
 *
 * See the included NOTICE file for GPLv3 section 7 terms.
 */

package app.morphe.extension.shared.fixes.gallery;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPOutputStream;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GalleryMetadataResponsePatchTest {
    private static final MediaType JSON = MediaType.get("application/json");

    private static JSONObject post(String thumbnail, JSONObject metadata) throws Exception {
        JSONObject data = new JSONObject()
                .put("id", "abc")
                .put("is_gallery", true)
                .put("thumbnail", thumbnail)
                .put("media_metadata", metadata)
                .put("gallery_data", new JSONObject().put("items", new JSONArray()
                        .put(new JSONObject().put("media_id", "aaa111").put("id", 1))
                        .put(new JSONObject().put("media_id", "bbb222").put("id", 2))));
        JSONObject child = new JSONObject().put("kind", "t3").put("data", data);
        return new JSONObject().put("kind", "Listing").put("data",
                new JSONObject().put("children", new JSONArray().put(child)));
    }

    private static JSONObject failedMetadata() throws Exception {
        return new JSONObject()
                .put("aaa111", new JSONObject().put("status", "failed").put("id", "aaa111"))
                .put("bbb222", new JSONObject().put("status", "failed").put("id", "bbb222"));
    }

    private static JSONObject metadataOf(String body) throws Exception {
        return new JSONObject(body).getJSONObject("data").getJSONArray("children")
                .getJSONObject(0).getJSONObject("data").getJSONObject("media_metadata");
    }

    @Test
    public void rebuildsFailedItemsAsValidImages() throws Exception {
        JSONObject listing = post("https://b.thumbs.redditmedia.com/x.png", failedMetadata());
        assertTrue(GalleryMetadataResponsePatch.repair(listing));
        JSONObject item = listing.getJSONObject("data").getJSONArray("children").getJSONObject(0)
                .getJSONObject("data").getJSONObject("media_metadata").getJSONObject("aaa111");
        assertEquals("valid", item.getString("status"));
        assertEquals("Image", item.getString("e"));
        assertEquals("image/png", item.getString("m"));
        assertEquals("https://i.redd.it/aaa111.png", item.getJSONObject("s").getString("u"));
        assertEquals(1, item.getJSONArray("p").length());
    }

    @Test
    public void fallsBackToJpegWithoutUsableThumbnail() throws Exception {
        JSONObject listing = post("nsfw", failedMetadata());
        GalleryMetadataResponsePatch.repair(listing);
        JSONObject item = listing.getJSONObject("data").getJSONArray("children").getJSONObject(0)
                .getJSONObject("data").getJSONObject("media_metadata").getJSONObject("bbb222");
        assertEquals("https://i.redd.it/bbb222.jpeg", item.getJSONObject("s").getString("u"));
        assertEquals("image/jpg", item.getString("m"));
    }

    @Test
    public void leavesValidItemsAlone() throws Exception {
        JSONObject valid = new JSONObject().put("status", "valid").put("e", "Image").put("id", "aaa111")
                .put("s", new JSONObject().put("u", "https://preview.redd.it/real.jpg"));
        JSONObject listing = post("x.jpg", new JSONObject().put("aaa111", valid));
        assertFalse(GalleryMetadataResponsePatch.repair(listing));
    }

    @Test
    public void rewritesResponsesThroughOkHttp() throws Exception {
        String body = post("https://b.thumbs.redditmedia.com/x.jpg", failedMetadata()).toString();
        try (Response response = client(body.getBytes("UTF-8"), null)
                .newCall(new Request.Builder().url("https://oauth.reddit.com/comments/abc.json").build())
                .execute()) {
            JSONObject item = metadataOf(response.body().string()).getJSONObject("aaa111");
            assertEquals("https://i.redd.it/aaa111.jpeg", item.getJSONObject("s").getString("u"));
        }
    }

    @Test
    public void handlesGzipEncodedBodies() throws Exception {
        String body = post("x.gif", failedMetadata()).toString();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(body.getBytes("UTF-8"));
        }
        try (Response response = client(out.toByteArray(), "gzip")
                .newCall(new Request.Builder().url("https://oauth.reddit.com/r/pics/hot").build())
                .execute()) {
            assertEquals(null, response.header("Content-Encoding"));
            JSONObject item = metadataOf(response.body().string()).getJSONObject("bbb222");
            assertEquals("https://i.redd.it/bbb222.gif", item.getJSONObject("s").getString("u"));
        }
    }

    @Test
    public void ignoresNonRedditHosts() throws Exception {
        String body = post("x.jpg", failedMetadata()).toString();
        try (Response response = client(body.getBytes("UTF-8"), null)
                .newCall(new Request.Builder().url("https://example.com/comments/abc.json").build())
                .execute()) {
            assertEquals("failed", metadataOf(response.body().string())
                    .getJSONObject("aaa111").getString("status"));
        }
    }

    private static OkHttpClient client(byte[] body, String encoding) {
        GalleryMetadataResponsePatch patch = new GalleryMetadataResponsePatch() {
            @Override
            public boolean isPatchIncluded() {
                return true;
            }
        };
        return new OkHttpClient.Builder()
                .addInterceptor(patch)
                .addInterceptor(chain -> {
                    Response.Builder builder = new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create(body, JSON));
                    if (encoding != null) builder.header("Content-Encoding", encoding);
                    return builder.build();
                })
                .build();
    }
}
