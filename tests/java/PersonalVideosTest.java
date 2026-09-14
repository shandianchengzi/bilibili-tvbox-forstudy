package com.github.catvod.spider.bili;

import android.content.SharedPreferences;
import com.github.catvod.spider.BiliStudy;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic authenticated transactions; no real account or personal data is required. */
public final class PersonalVideosTest {
    private static int checks;
    private static final String BV = "BV0000000001";

    public static void main(String[] args) throws Exception {
        normalizesWithoutInventingStatistics();
        enrichesOnlyNeededFieldsAndSeparatesSessions();
        partialMetadataAndSessionChanges();
        personalCallbacksAndCursors();
        movieCallback();
        System.out.println("PersonalVideosTest: " + checks + " assertions passed");
    }

    private static void normalizesWithoutInventingStatistics() throws Exception {
        JSONObject favorite = favorite(BV, 3600, 10000, 8, 1700000001L);
        JSONObject row = PersonalVideos.favorites(new JSONArray().put(favorite)).getJSONObject(0);
        equal(10000L, row.getLong("play"), "Favorite playback uses cnt_info.play");
        equal(8L, row.getLong("favorites"), "Favorite count uses cnt_info.collect");
        equal(12L, row.getLong("video_review"), "Favorite danmaku remains distinct from collection count");
        equal(1700000001L, row.getLong("pubdate"), "Video pubtime is retained");
        favorite.remove("pubtime"); favorite.put("fav_time", 1999999999);
        check(!PersonalVideos.favorites(new JSONArray().put(favorite)).getJSONObject(0).has("pubdate"), "Favorite time cannot replace video publication");
        JSONObject dynamic = dynamic(BV, "61:00", "1.2万");
        row = PersonalVideos.dynamics(new JSONArray().put(dynamic)).getJSONObject(0);
        equal(3660L, row.getLong("duration"), "Dynamic text duration is converted to seconds");
        check(!row.has("play"), "Rounded dynamic view labels remain unknown");
        check(!row.has("pubdate"), "Dynamic timestamp is not video publication time");
        equal(1, PersonalVideos.dynamics(new JSONArray().put(new JSONObject().put("orig", dynamic))).length(), "Forwarded videos are normalized");
        row = PersonalVideos.history(new JSONArray().put(history(BV, 900))).getJSONObject(0);
        equal(900L, row.getLong("duration"), "History exposes actual duration");
        check(!row.has("pubdate") && !row.has("play"), "History watch time and progress are not public statistics");
        JSONObject pgc = new JSONObject().put("title", "Episode").put("duration", 1200)
                .put("history", new JSONObject().put("epid", 9));
        row = PersonalVideos.history(new JSONArray().put(pgc)).getJSONObject(0);
        equal("ep:9", row.getString("vod_id"), "PGC history remains playable without a BV");
        check(!row.has("play"), "Episode metadata does not invent a season-wide play count");
    }

    private static void enrichesOnlyNeededFieldsAndSeparatesSessions() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BiliClient client = client(url -> {
            check("/x/web-interface/wbi/view".equals(url.getPath()), "Metadata uses the normal signed view endpoint");
            Map<String, String> query = query(url);
            check(query.containsKey("w_rid") && query.containsKey("wts"), "Metadata request is WBI signed");
            calls.incrementAndGet();
            return response(url, new JSONObject().put("bvid", query.get("bvid")).put("duration", 3660)
                    .put("pubdate", 1700000100L).put("stat", new JSONObject().put("view", 20000)
                            .put("danmaku", 500).put("favorite", 700)));
        });
        PersonalVideos metadata = new PersonalVideos();
        JSONArray source = PersonalVideos.history(new JSONArray().put(history(BV, 3600)));
        metadata.enrich(client, source, new HashMap<>());
        equal(0, calls.get(), "Original-order personal lists do not fetch public metadata");
        HashMap<String, String> selected = filters("duration", "4");
        metadata.enrich(client, source, selected);
        equal(0, calls.get(), "Known duration alone needs no metadata request");
        selected.put("plays", "1k_10k");
        JSONObject enriched = metadata.enrich(client, new JSONArray().put(source.get(0)).put(source.get(0)), selected);
        equal(1, calls.get(), "Repeated BV metadata is fetched once");
        equal(20000L, enriched.getJSONArray("list").getJSONObject(0).getLong("play"), "Exact view count fills the missing history field");
        check(!enriched.getBoolean("incomplete"), "Required known fields are complete");
        check(!source.getJSONObject(0).has("play"), "Enrichment does not mutate the input list");
        equal(3600L, enriched.getJSONArray("list").getJSONObject(0).getLong("duration"), "Enrichment preserves the known duration of the personal-list item");
        selected.clear(); selected.put("order", "stow");
        metadata.enrich(client, source, selected);
        equal(1, calls.get(), "Changing sort reuses public statistics from the same video");
        client.cancelQr();
        metadata.enrich(client, source, selected);
        equal(2, calls.get(), "A changed local session invalidates the metadata cache");
        JSONArray favorites = PersonalVideos.favorites(new JSONArray().put(favorite(BV, 3600, 1000, 50, 1700000000L)));
        metadata.enrich(client, favorites, filters("order", "click", "plays", "1k_10k", "duration", "4"));
        equal(2, calls.get(), "Complete favorite fields do not trigger redundant view requests");
    }

    private static void partialMetadataAndSessionChanges() throws Exception {
        BiliClient unavailable = client(url -> new Response(url, new JSONObject().put("code", -404).toString()));
        JSONArray source = PersonalVideos.history(new JSONArray().put(history(BV, 3600)));
        JSONObject result = new PersonalVideos().enrich(unavailable, source, filters("plays", "lt_1k"));
        check(result.getBoolean("incomplete"), "Unavailable metadata is reported as partial");
        equal(0, VideoFilters.apply(result.getJSONArray("list"), filters("plays", "lt_1k"), false).length(), "An unavailable view count cannot enter the lowest range");
        final BiliClient[] holder = new BiliClient[1];
        holder[0] = client(url -> {
            holder[0].cancelQr();
            return response(url, new JSONObject().put("bvid", BV).put("stat", new JSONObject().put("view", 1)));
        });
        boolean rejected = false;
        try { new PersonalVideos().enrich(holder[0], source, filters("plays", "lt_1k")); }
        catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "Metadata returned after a session change cannot be shown as current-account data");
    }

    private static void personalCallbacksAndCursors() throws Exception {
        List<URL> calls = new ArrayList<>();
        BiliClient client = client(url -> {
            calls.add(url);
            Map<String, String> query = query(url);
            String path = url.getPath();
            if (path.equals("/x/v3/fav/resource/list")) {
                equal("55", query.get("media_id"), "Selected favorite folder reaches the API");
                check(!query.containsKey("plays") && !query.containsKey("duration"), "Unsupported ranges stay on the device");
                return response(url, new JSONObject().put("has_more", false).put("medias", new JSONArray()
                        .put(favorite("BV0000000001", 4000, 100000, 2, 1700000000L))
                        .put(favorite("BV0000000002", 4000, 200000, 20, 1700000010L))
                        .put(favorite("BV0000000003", 60, 300000, 30, 1700000020L))));
            }
            if (path.equals("/x/polymer/web-dynamic/v1/feed/all")) {
                int page = Integer.parseInt(query.get("page"));
                equal(page == 1 ? "" : "cursor-" + page, query.get("offset"), "Dynamic cursor follows the actual upstream pages");
                return response(url, new JSONObject().put("has_more", true).put("offset", "cursor-" + (page + 1))
                        .put("items", new JSONArray().put(dynamic(bv(page), "61:00", page * 1000))));
            }
            if (path.equals("/x/web-interface/history/cursor")) {
                int page = query.containsKey("max") ? Integer.parseInt(query.get("max")) : 1;
                if (page > 1) {
                    equal(String.valueOf(1700000000L - page), query.get("view_at"), "History cursor retains its watch-time boundary");
                    equal("archive", query.get("business"), "History cursor retains business");
                }
                JSONArray rows = new JSONArray();
                for (int i = 0; i < 20; i++) rows.put(history(bv(page * 100 + i), i == 0 ? 4000 : 60));
                return response(url, new JSONObject().put("list", rows).put("cursor", new JSONObject()
                        .put("max", page + 1).put("view_at", 1700000000L - page - 1).put("business", "archive")));
            }
            throw new AssertionError("Unexpected personal request: " + path);
        });
        BiliStudy spider = new BiliStudy();
        set(spider, "client", client);
        HashMap<String, String> favorite = filters("fid", "55", "order", "stow", "duration", "4", "plays", "100k_plus");
        JSONObject favorites = new JSONObject(spider.categoryContent("favorites", "1", true, favorite));
        equal(2, favorites.getJSONArray("list").length(), "Favorite range and duration are combined");
        equal("video:BV0000000002", favorites.getJSONArray("list").getJSONObject(0).getString("vod_id"), "Favorite count sorting applies to the filtered batch");
        equal(1, calls.size(), "Complete favorite metadata needs only the folder-list request");

        HashMap<String, String> dynamic = filters("order", "click", "duration", "4", "plays", "1k_10k");
        JSONObject first = new JSONObject(spider.categoryContent("dynamic", "1", true, dynamic));
        equal(3, first.getJSONArray("list").length(), "Dynamic filtering scans its three-page batch");
        equal("video:" + bv(3), first.getJSONArray("list").getJSONObject(0).getString("vod_id"), "Dynamic batch is sorted after all scanned pages are combined");
        JSONObject second = new JSONObject(spider.categoryContent("dynamic", "2", true, dynamic));
        equal("video:" + bv(6), second.getJSONArray("list").getJSONObject(0).getString("vod_id"), "Next dynamic batch starts from upstream page four");
        int before = calls.size();
        HashMap<String, String> changed = filters("duration", "4");
        JSONObject rejected = new JSONObject(spider.categoryContent("dynamic", "2", true, changed));
        check(rejected.getJSONArray("list").getJSONObject(0).getString("vod_id").startsWith("notice:"), "Changed dynamic filters require the first page");
        equal(before, calls.size(), "Changed filters cannot reuse another dynamic cursor");

        HashMap<String, String> history = filters("duration", "4");
        first = new JSONObject(spider.categoryContent("history", "1", true, history));
        equal(3, first.getJSONArray("list").length(), "History duration filtering scans the cursor chain");
        second = new JSONObject(spider.categoryContent("history", "2", true, history));
        equal("video:" + bv(400), second.getJSONArray("list").getJSONObject(0).getString("vod_id"), "History second batch uses the stored cursor from the first batch");
        before = calls.size();
        client.cancelQr();
        rejected = new JSONObject(spider.categoryContent("history", "2", true, history));
        check(rejected.getJSONArray("list").getJSONObject(0).getString("vod_id").startsWith("notice:"), "Session changes invalidate personal cursor chains");
        equal(before, calls.size(), "An old-account cursor cannot trigger an API request");
    }

    private static void movieCallback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BiliClient client = client(url -> {
            calls.incrementAndGet();
            Map<String, String> q = query(url);
            equal("2", q.get("season_type"), "Movie callback uses movie index");
            equal("6", q.get("order"), "Recently released uses actual release ordering");
            equal("0", q.get("sort"), "Newest release is first");
            equal("2", q.get("page"), "Release sort persists during pagination");
            equal("1", q.get("season_status"), "Release sort combines with free titles");
            return response(url, new JSONObject().put("list", new JSONArray().put(
                    new JSONObject().put("season_id", 99).put("title", "Synthetic movie"))).put("has_next", 1));
        });
        BiliStudy spider = new BiliStudy(); set(spider, "client", client);
        JSONObject result = new JSONObject(spider.categoryContent("pgc:2", "2", true, filters("order", "6", "season_status", "1")));
        equal("season:99", result.getJSONArray("list").getJSONObject(0).getString("vod_id"), "Release-ordered movies remain playable");
        equal(3, result.getInt("pagecount"), "Movie release ordering preserves next page");
        equal(1, calls.get(), "Movie sorting makes one normal list request");
    }

    private static JSONObject favorite(String bvid, int duration, long plays, long favorites, long pubdate) throws Exception {
        return new JSONObject().put("bvid", bvid).put("title", "Favorite " + bvid).put("cover", "https://i0.hdslb.com/f.jpg")
                .put("duration", duration).put("pubtime", pubdate)
                .put("cnt_info", new JSONObject().put("play", plays).put("collect", favorites).put("danmaku", 12));
    }
    private static JSONObject dynamic(String bvid, String duration, Object plays) throws Exception {
        return new JSONObject().put("modules", new JSONObject().put("module_author", new JSONObject().put("pub_ts", 1999999999L))
                .put("module_dynamic", new JSONObject().put("major", new JSONObject().put("archive",
                        new JSONObject().put("bvid", bvid).put("title", "Dynamic " + bvid).put("duration_text", duration)
                                .put("stat", new JSONObject().put("play", plays).put("danmaku", 20))))));
    }
    private static JSONObject history(String bvid, int duration) throws Exception {
        return new JSONObject().put("title", "History " + bvid).put("cover", "https://i0.hdslb.com/h.jpg")
                .put("duration", duration).put("view_at", 1999999999L).put("progress", 999999)
                .put("history", new JSONObject().put("bvid", bvid).put("business", "archive"));
    }
    private static String bv(int value) { return String.format(java.util.Locale.ROOT, "BV%010d", value); }
    private static HashMap<String, String> filters(String... pairs) {
        HashMap<String, String> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put(pairs[i], pairs[i + 1]);
        return values;
    }

    private interface TestTransport { HttpURLConnection open(URL url) throws Exception; }

    private static BiliClient client(TestTransport transport) throws Exception {
        Map<String, String> saved = new LinkedHashMap<>();
        saved.put("cookies", new JSONObject().put("SESSDATA", new JSONObject().put("value", "synthetic-session").put("expires", 0))
                .put("DedeUserID", new JSONObject().put("value", "7").put("expires", 0)).toString());
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(PersonalVideosTest.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class}, (proxy, method, args) -> {
                    if ("getString".equals(method.getName())) return saved.containsKey(args[0]) ? saved.get(args[0]) : args[1];
                    if ("edit".equals(method.getName())) return Proxy.newProxyInstance(PersonalVideosTest.class.getClassLoader(),
                            new Class<?>[] {SharedPreferences.Editor.class}, (editor, edit, values) -> {
                                if ("remove".equals(edit.getName())) { saved.remove(values[0]); return editor; }
                                if ("putString".equals(edit.getName())) { saved.put((String) values[0], (String) values[1]); return editor; }
                                if ("commit".equals(edit.getName())) return true;
                                throw new AssertionError(edit.getName());
                            });
                    throw new AssertionError(method.getName());
                });
        BiliClient client = new BiliClient(preferences, url -> {
            try { return transport.open(url); }
            catch (IOException failure) { throw failure; }
            catch (Exception failure) { throw new IOException("Synthetic transport failed", failure); }
        });
        set(client, "mixinKey", "ea1db124af3c7062474693fa704f4ff8");
        set(client, "mixinExpires", Long.MAX_VALUE);
        set(client, "visitorAttempt", System.currentTimeMillis());
        return client;
    }
    private static Map<String, String> query(URL url) throws Exception {
        Map<String, String> values = new HashMap<>();
        for (String entry : url.getQuery().split("&")) {
            String[] pair = entry.split("=", 2);
            values.put(URLDecoder.decode(pair[0], "UTF-8"), URLDecoder.decode(pair.length > 1 ? pair[1] : "", "UTF-8"));
        }
        return values;
    }
    private static HttpURLConnection response(URL url, JSONObject data) throws Exception {
        return new Response(url, new JSONObject().put("code", 0).put("data", data).toString());
    }
    private static final class Response extends HttpURLConnection {
        final String body;
        Response(URL url, String body) { super(url); this.body = body; }
        public int getResponseCode() { return 200; }
        public InputStream getInputStream() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
        public Map<String, List<String>> getHeaderFields() { return Collections.emptyMap(); }
        public String getHeaderField(String name) { return null; }
        public void connect() {}
        public void disconnect() {}
        public boolean usingProxy() { return false; }
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(expected.equals(actual), message + " expected=" + expected + " actual=" + actual);
    }
    private static void check(boolean success, String message) {
        checks++; if (!success) throw new AssertionError(message);
    }
}
