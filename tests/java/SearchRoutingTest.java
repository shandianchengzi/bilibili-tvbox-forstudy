package com.github.catvod.spider.bili;

import android.content.SharedPreferences;
import com.github.catvod.spider.BiliStudy;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Real TVBox callbacks and WBI signing with deterministic public-search HTTP responses. */
public final class SearchRoutingTest {
    private static int assertions;
    private static final String KEYWORD = "红星照耀中国";
    private static final String BV = "BV1234567890";

    public static void main(String[] args) throws Exception {
        separatesShowsFromUploaderVideos();
        retainsResultsWhenOneMediaTypeFails();
        neverFallsBackToUploaderSearch();
        retainsSingleFileAudiobooks();
        scopesZhouSearch();
        preservesExplicitIds();
        validatesMediaSearchParameters();
        preparesAnonymousVisitor();
        System.out.println("SearchRoutingTest: " + assertions + " assertions passed");
        if ("1".equals(System.getenv("BILI_SEARCH_LIVE_SMOKE"))) liveSmoke();
    }

    /** One public query; no credentials, alternate endpoints or retries on rate limits. */
    private static void liveSmoke() throws Exception {
        BiliClient client = new BiliClient(preferences());
        try {
            JSONArray videos = liveRows(client.search(KEYWORD, 1, "totalrank"), "video");
            check(videos.length() > 0, "Live user query returns uploader videos");
            JSONArray films = liveRows(client.searchMedia("media_ft", KEYWORD, 1), "media_ft");
            JSONArray bangumi = liveRows(client.searchMedia("media_bangumi", KEYWORD, 1), "media_bangumi");
            System.out.println("SearchRoutingTest live: query=" + KEYWORD + " video_hits=" + videos.length()
                    + " media_ft_hits=" + films.length() + " media_bangumi_hits=" + bangumi.length());
            String firstBv = videos.getJSONObject(0).optString("bvid");
            check(firstBv.matches("BV[0-9A-Za-z]{10}"), "Live uploader search provides a BV identity");
            JSONObject view = client.get("/x/web-interface/wbi/view", Collections.singletonMap("bvid", firstBv));
            JSONArray parts = view.optJSONArray("pages");
            check(parts != null && parts.length() > 0, "Live BV view exposes actual playable parts");
            System.out.println("SearchRoutingTest live: first_bv=" + firstBv + " parts=" + parts.length()
                    + " has_ugc_season=" + (view.optJSONObject("ugc_season") != null));
        } catch (Exception failure) {
            if (!blocked(failure)) throw failure;
            System.out.println("::warning::SearchRoutingTest live: BLOCKED by Bilibili rate/risk control; stopped without alternate endpoints or proxies");
        }
    }

    private static JSONArray liveRows(JSONObject data, String kind) {
        JSONArray rows = data.optJSONArray("result");
        check(rows != null, "Live " + kind + " response has a result array");
        return rows;
    }

    private static boolean blocked(Exception failure) {
        if (failure instanceof BiliClient.ApiException) {
            int code = ((BiliClient.ApiException) failure).code;
            return code == -412 || code == -352 || code == -509;
        }
        String message = failure.getMessage();
        return failure instanceof IOException && message != null
                && (message.contains("HTTP 412") || message.contains("HTTP 429"));
    }

    private static void separatesShowsFromUploaderVideos() throws Exception {
        Fixture fixture = new Fixture("media", KEYWORD, 2);
        fixture.reply("media_ft", data(2, season(101), season(999), video()));
        fixture.reply("media_bangumi", data(5, season(999), season(202), season(0)));
        JSONObject result = fixture.search();
        ids(result, "season:101", "season:999", "season:202");
        equal(2, result.getInt("page"), "Requested media page is preserved");
        equal(3, result.getInt("pagecount"), "More pages from either media type enable the next page");
        fixture.complete();

        Fixture reverse = new Fixture("media", KEYWORD, 2);
        reverse.reply("media_ft", data(5, season(101)));
        reverse.reply("media_bangumi", data(2, season(202)));
        equal(3, reverse.search().getInt("pagecount"), "Movie pagination also enables the next page independently");
        reverse.complete();
    }

    private static void retainsResultsWhenOneMediaTypeFails() throws Exception {
        for (String failed : Arrays.asList("media_ft", "media_bangumi")) {
            Fixture fixture = new Fixture("media", KEYWORD, 2);
            fixture.reply("media_ft", data(4, season(101)));
            fixture.reply("media_bangumi", data(4, season(202)));
            fixture.fail(failed);
            JSONObject result = fixture.search();
            ids(result, "media_ft".equals(failed) ? "season:202" : "season:101");
            equal(3, result.getInt("pagecount"), "A failed media type cannot remove the surviving next page");
            fixture.complete();
        }
    }

    private static void neverFallsBackToUploaderSearch() throws Exception {
        Fixture failed = new Fixture("media", KEYWORD, 1);
        failed.fail("media_ft");
        failed.fail("media_bangumi");
        JSONObject error = failed.search();
        JSONArray rows = error.getJSONArray("list");
        equal(1, rows.length(), "Both failed endpoints produce one error notice");
        check(rows.getJSONObject(0).getString("vod_id").startsWith("notice:"), "Failed media searches cannot return uploader videos");
        check(!error.optString("msg").isEmpty(), "Both failed endpoints expose a useful error");
        failed.complete();

        Fixture empty = new Fixture("media", KEYWORD, 3);
        empty.reply("media_ft", data(3));
        empty.reply("media_bangumi", data(3));
        JSONObject result = empty.search();
        ids(result);
        equal(3, result.getInt("pagecount"), "Empty final media page does not request another page");
        empty.complete();

        Fixture malformed = new Fixture("media", KEYWORD, 1);
        malformed.reply("media_ft", new JSONObject());
        malformed.reply("media_bangumi", data(1, season(202)));
        ids(malformed.search(), "season:202");
        malformed.complete();
    }

    private static void retainsSingleFileAudiobooks() throws Exception {
        Fixture study = new Fixture("study", KEYWORD, 2);
        // An entire audiobook uploaded as one ordinary BV must remain discoverable.
        study.reply("video", data(7, video()));
        JSONObject result = study.search();
        ids(result, "video:" + BV);
        equal(KEYWORD + " 完整有声书", result.getJSONArray("list").getJSONObject(0).getString("vod_name"),
                "Single-file audiobook title is retained");
        equal(3, result.getInt("pagecount"), "Uploader-video search retains pagination");
        study.complete();
    }

    private static void scopesZhouSearch() throws Exception {
        Fixture scoped = new Fixture("zhou_shen", "周深 大鱼", 1);
        scoped.reply("video", data(1, video()));
        ids(new JSONObject(scoped.spider.searchContent("大鱼", false)), "video:" + BV);
        scoped.complete();
        Fixture named = new Fixture("zhou_shen", "周深 大鱼", 1);
        named.reply("video", data(1, video()));
        ids(named.search(), "video:" + BV);
        named.complete();
    }

    private static void preservesExplicitIds() throws Exception {
        String[][] lookups = {
                {BV, "video:" + BV},
                {"https://www.bilibili.com/video/" + BV + "/", "video:" + BV},
                {"ss12345", "season:12345"},
                {"https://www.bilibili.com/bangumi/play/ep67890", "ep:67890"}
        };
        for (String mode : Arrays.asList("media", "study", "zhou_shen")) {
            Fixture fixture = new Fixture(mode, "", 1);
            for (String[] lookup : lookups)
                ids(new JSONObject(fixture.spider.searchContent(lookup[0], false)), lookup[1]);
            fixture.complete();
        }
    }

    private static void validatesMediaSearchParameters() throws Exception {
        Fixture invalid = new Fixture("media", "", 1);
        set(invalid.client, "visitorAttempt", 0L);
        for (String kind : new String[] {null, "", "video", "media", "MEDIA_FT"}) {
            try {
                invalid.client.searchMedia(kind, KEYWORD, 1);
                throw new AssertionError("Unsupported media kind was accepted");
            } catch (IllegalArgumentException expected) {
                check(true, "Unsupported kind is rejected before visitor or search requests");
            }
        }
        invalid.complete();
        Fixture normalized = new Fixture("media", "", 1);
        normalized.reply("media_ft", data(1));
        normalized.client.searchMedia("media_ft", null, -2);
        normalized.complete();
        Fixture trimmed = new Fixture("media", KEYWORD, 1);
        trimmed.reply("media_bangumi", data(1));
        trimmed.client.searchMedia("media_bangumi", "  " + KEYWORD + "  ", 1);
        trimmed.complete();
    }

    private static void preparesAnonymousVisitor() throws Exception {
        List<String> paths = new ArrayList<>();
        BiliClient client = new BiliClient(preferences(), url -> {
            paths.add(url.getPath());
            return new Response(url, new JSONObject().put("code", 0).put("data", new JSONObject()).toString());
        });
        signedKey(client);
        client.searchMedia("media_ft", KEYWORD, 1);
        equal(Arrays.asList("/x/frontend/finger/spi", "/x/web-interface/wbi/search/type"), paths,
                "Licensed-show search prepares the visitor through the same API as uploader search");
    }

    private static JSONObject season(long id) throws Exception {
        return new JSONObject().put("season_id", id).put("title", KEYWORD + " 影视 " + id)
                .put("cover", "https://example.invalid/show.jpg");
    }

    private static JSONObject video() throws Exception {
        return new JSONObject().put("bvid", BV).put("title", KEYWORD + " 完整有声书")
                .put("duration", "180:00").put("author", "示例朗读者")
                .put("pic", "https://example.invalid/audiobook.jpg");
    }

    private static JSONObject data(int pages, JSONObject... rows) throws Exception {
        JSONArray result = new JSONArray();
        for (JSONObject row : rows) result.put(row);
        return new JSONObject().put("numPages", pages).put("result", result);
    }

    private static void ids(JSONObject result, String... expected) throws Exception {
        JSONArray list = result.getJSONArray("list");
        equal(expected.length, list.length(), "Search contains exactly the expected number of entries");
        for (int i = 0; i < expected.length; i++)
            equal(expected[i], list.getJSONObject(i).getString("vod_id"), "Search result identity and order");
    }

    private static final class Fixture {
        final String keyword;
        final int page;
        final Map<String, String> responses = new LinkedHashMap<>();
        final List<URL> requests = new CopyOnWriteArrayList<>();
        final List<Response> opened = new CopyOnWriteArrayList<>();
        final BiliStudy spider = new BiliStudy();
        final BiliClient client;

        Fixture(String mode, String keyword, int page) throws Exception {
            this.keyword = keyword;
            this.page = page;
            client = new BiliClient(preferences(), url -> {
                requests.add(url);
                String kind = query(url).get("search_type");
                String body = responses.get(kind);
                if (body == null) throw new IOException("Unexpected endpoint or intentionally failed search type");
                Response response = new Response(url, body);
                opened.add(response);
                return response;
            });
            signedKey(client);
            set(client, "visitorAttempt", System.currentTimeMillis());
            set(spider, "client", client);
            set(spider, "mode", mode);
        }

        void reply(String kind, JSONObject data) throws Exception {
            responses.put(kind, new JSONObject().put("code", 0).put("data", data).toString());
        }

        void fail(String kind) { responses.put(kind, null); }

        JSONObject search() throws Exception {
            return new JSONObject(spider.searchContent(keyword, false, Integer.toString(page)));
        }

        void complete() throws Exception {
            equal(responses.size(), requests.size(), "Exactly the intended search types are requested, with no uploader fallback");
            List<String> seen = new ArrayList<>();
            for (URL url : requests) {
                equal("api.bilibili.com", url.getHost(), "Search stays on the official API host");
                equal("/x/web-interface/wbi/search/type", url.getPath(), "Search uses the WBI endpoint");
                Map<String, String> query = query(url);
                String kind = query.get("search_type");
                check(responses.containsKey(kind), "Every request uses an explicitly expected search type");
                check(!seen.contains(kind), "Each media type is searched only once");
                seen.add(kind);
                equal(keyword, query.get("keyword"), "Wire keyword preserves the selected module scope");
                equal(Integer.toString(page), query.get("page"), "Wire search page is correct");
                equal("video".equals(kind) ? 7 : 5, query.size(), "Only known search fields and the WBI signature are transmitted");
                if ("video".equals(kind)) {
                    equal("totalrank", query.get("order"), "Uploader search preserves relevance order");
                    equal("0", query.get("duration"), "Uploader search includes all durations");
                }
                check(query.get("w_rid").matches("[0-9a-f]{32}"), "Search carries a WBI signature");
                check(Long.parseLong(query.get("wts")) > 0, "Search carries a WBI timestamp");
            }
            for (Response response : opened) check(response.closed, "Search releases every HTTP response");
        }
    }

    private static Map<String, String> query(URL url) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        if (url.getQuery() == null) return result;
        for (String pair : url.getQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], "UTF-8"),
                    URLDecoder.decode(parts.length > 1 ? parts[1] : "", "UTF-8"));
        }
        return result;
    }

    private static SharedPreferences preferences() {
        Map<String, String> saved = new LinkedHashMap<>();
        return (SharedPreferences) Proxy.newProxyInstance(SearchRoutingTest.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class}, (proxy, method, args) -> {
                    if ("getString".equals(method.getName()))
                        return saved.containsKey(args[0]) ? saved.get(args[0]) : args[1];
                    if ("edit".equals(method.getName())) {
                        Map<String, String> staged = new LinkedHashMap<>();
                        return Proxy.newProxyInstance(SearchRoutingTest.class.getClassLoader(),
                                new Class<?>[] {SharedPreferences.Editor.class}, (editor, edit, values) -> {
                                    if ("putString".equals(edit.getName())) {
                                        staged.put((String) values[0], (String) values[1]); return editor;
                                    }
                                    if ("remove".equals(edit.getName())) {
                                        staged.put((String) values[0], null); return editor;
                                    }
                                    if ("commit".equals(edit.getName())) {
                                        for (Map.Entry<String, String> entry : staged.entrySet()) {
                                            if (entry.getValue() == null) saved.remove(entry.getKey());
                                            else saved.put(entry.getKey(), entry.getValue());
                                        }
                                        return true;
                                    }
                                    throw new AssertionError("Unexpected preference editor operation: " + edit.getName());
                                });
                    }
                    throw new AssertionError("Unexpected preference operation: " + method.getName());
                });
    }

    private static final class Response extends HttpURLConnection {
        final String body;
        volatile boolean closed;
        Response(URL url, String body) { super(url); this.body = body; }
        public int getResponseCode() { return 200; }
        public Map<String, List<String>> getHeaderFields() { return Collections.emptyMap(); }
        public String getHeaderField(String name) { return null; }
        public InputStream getInputStream() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
        public void connect() { }
        public boolean usingProxy() { return false; }
        public void disconnect() { closed = true; }
    }

    private static void signedKey(BiliClient client) throws Exception {
        set(client, "mixinKey", "ea1db124af3c7062474693fa704f4ff8");
        set(client, "mixinExpires", Long.MAX_VALUE);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void equal(Object expected, Object actual, String message) {
        check(expected.equals(actual), message + " expected=" + expected + " actual=" + actual);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
