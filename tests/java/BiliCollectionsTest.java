package com.github.catvod.spider.bili;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Offline collection contracts. All network responses are synthetic and contain no credentials. */
public final class BiliCollectionsTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        ordinaryVideosRemainOrdinary();
        nestedEpisodesAndSeedParts();
        embeddedPageVariants();
        namedSectionsAndMissingDeclaredParts();
        multiplePagesAndActualTransport();
        repeatedPagesAndPartialFailure();
        unknownTotalsAndInvalidRows();
        budgetAndCancellation();
        System.out.println("BiliCollectionsTest: " + assertions + " assertions passed");
    }

    private static void ordinaryVideosRemainOrdinary() throws Exception {
        equal(null, BiliCollections.expand(null, new JSONObject()), "ordinary video does not become a collection");
        equal(null, BiliCollections.expand(null, null), "missing view has no collection");
        JSONObject view = seed(1).put("pages", new JSONArray().put(part(11, "上")).put(part(12, "下")));
        equal(null, BiliCollections.expand(null, view), "multi-P without ugc_season is left to existing detail logic");
        view.put("ugc_season", new JSONObject().put("id", -1));
        equal(null, BiliCollections.expand(null, view), "invalid season ID does not create a false collection");
    }

    private static void nestedEpisodesAndSeedParts() throws Exception {
        JSONObject view = seed(1).put("pages", new JSONArray().put(part(11, "第一章")).put(part(12, "第二章")));
        JSONArray first = new JSONArray().put(episode(1, 11)).put(episode(1, 11))
                .put(new JSONObject().put("arc", new JSONObject().put("bvid", bv(2)).put("cid", 21).put("title", "嵌套标题")));
        JSONArray second = new JSONArray().put(new JSONObject().put("bvid", bv(3)).put("title", "缺少CID的章节"));
        view.put("ugc_season", season(3, first, second));
        JSONObject expanded = BiliCollections.expand(view, (params, remaining) -> {
            throw new AssertionError("complete inline collection must not load pages");
        }, () -> 0);
        equal("合集真实标题", expanded.getString("name"), "use collection title rather than clicked episode title");
        equal("https://example.test/collection.jpg", expanded.getString("pic"), "use collection cover");
        equal("合集真实简介", expanded.getString("description"), "use collection introduction");
        equal(4, expanded.getInt("count"), "seed parts survive collection expansion without duplicates");
        equal(3, expanded.getInt("archive_count"), "archive count is distinct from playable part count");
        equal(3, expanded.getInt("total"), "total remains provider archive count");
        check(expanded.getBoolean("complete"), "all declared archives are present");
        JSONArray entries = expanded.getJSONArray("entries");
        equal(11L, entries.getJSONObject(0).getLong("cid"), "first seed part preserves CID");
        equal(12L, entries.getJSONObject(1).getLong("cid"), "second seed part remains reachable");
        equal("点击的视频 · P2 第二章", entries.getJSONObject(1).getString("title"), "part title is explicit");
        equal(bv(2), entries.getJSONObject(2).getString("bvid"), "nested arc BV parsed");
        equal(21L, entries.getJSONObject(2).getLong("cid"), "nested arc CID parsed");
        equal("嵌套标题", entries.getJSONObject(2).getString("title"), "nested title parsed");
        equal(0L, entries.getJSONObject(3).getLong("cid"), "missing CID stays unresolved for playback lookup");
        equal(bv(3), entries.getJSONObject(3).getString("bvid"), "section order remains author order");

        view = seed(1).put("ugc_season", season(2, new JSONArray().put(episode(2, 22))));
        expanded = BiliCollections.expand(null, view);
        equal(2, expanded.getInt("count"), "seed omitted by inline response is retained once");
        equal(bv(2), expanded.getJSONArray("entries").getJSONObject(0).getString("bvid"), "inline author order remains first");

        JSONObject duplicate = episode(2, 0);
        duplicate.remove("cid");
        view = seed(1).put("ugc_season", season(2, new JSONArray().put(duplicate).put(episode(2, 22)).put(episode(1, 11))));
        expanded = BiliCollections.expand(null, view);
        equal(2, expanded.getInt("count"), "resolved CID replaces an earlier unresolved entry");
        equal(22L, expanded.getJSONArray("entries").getJSONObject(0).getLong("cid"), "resolved duplicate keeps useful CID");
    }

    private static void embeddedPageVariants() throws Exception {
        JSONObject withPage = episode(2, 0).put("page", part(22, "第二章"));
        JSONObject withPages = episode(3, 31).put("pages", new JSONArray().put(part(31, "上篇")).put(part(32, "下篇")));
        JSONObject view = seed(1).put("ugc_season", season(3, new JSONArray()
                .put(episode(1, 11)).put(withPage).put(withPages)));
        JSONObject expanded = BiliCollections.expand(null, view);
        JSONArray entries = expanded.getJSONArray("entries");
        equal(4, entries.length(), "non-seed inline multi-P archive keeps every part");
        equal(22L, entries.getJSONObject(1).getLong("cid"), "episode.page CID is supported");
        equal(32L, entries.getJSONObject(3).getLong("cid"), "episode.pages second CID is supported");
        check(expanded.getBoolean("complete"), "inline page variants complete the collection");

        JSONObject season = view.getJSONObject("ugc_season");
        season.remove("title"); season.remove("cover"); season.remove("intro");
        season.put("ep_count", 4);
        expanded = BiliCollections.expand(view, (params, remaining) -> response(rows(1, 4), 4)
                .put("meta", new JSONObject().put("name", "分页提供的合集标题").put("cover", "https://example.test/meta.jpg")
                        .put("description", "分页提供的合集简介")), () -> 0);
        equal("分页提供的合集标题", expanded.getString("name"), "page metadata supplies absent collection title");
        equal("分页提供的合集简介", expanded.getString("description"), "page metadata supplies absent collection introduction");
        equal(5, expanded.getInt("count"), "filling pages does not erase an existing archive's extra part");
    }

    private static void namedSectionsAndMissingDeclaredParts() throws Exception {
        JSONObject view = seed(1).put("ugc_season", season(2,
                new JSONArray().put(episode(1, 11).put("title", "导读")),
                new JSONArray().put(episode(2, 21).put("title", "导读"))));
        JSONArray sections = view.getJSONObject("ugc_season").getJSONArray("sections");
        sections.getJSONObject(0).put("title", "上篇");
        sections.getJSONObject(1).put("title", "下篇");
        JSONObject expanded = BiliCollections.expand(null, view);
        equal("上篇 · 导读", expanded.getJSONArray("entries").getJSONObject(0).getString("title"),
                "first named section disambiguates identical episode names");
        equal("下篇 · 导读", expanded.getJSONArray("entries").getJSONObject(1).getString("title"),
                "second named section and author order are retained");

        for (boolean nested : new boolean[] {false, true}) {
            JSONObject episode = episode(2, 21);
            if (nested) episode.put("arc", new JSONObject().put("videos", 3));
            else episode.put("videos", 3);
            view = seed(1).put("ugc_season", season(2, new JSONArray().put(episode(1, 11)).put(episode)));
            expanded = BiliCollections.expand(view, (params, remaining) -> {
                throw new AssertionError("missing multi-P detail must not trigger recursive detail requests");
            }, () -> 0);
            equal(2, expanded.getInt("count"), "declared multi-P video retains its known first playable chapter");
            check(!expanded.getBoolean("complete"), "declared multi-P video without pages remains partial");
        }
    }

    private static void multiplePagesAndActualTransport() throws Exception {
        JSONObject view = seed(1).put("ugc_season", season(35, rows(1, 30)));
        view.getJSONObject("ugc_season").remove("mid");
        int[] requests = {0};
        int[] disconnected = {0};
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(BiliCollectionsTest.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getString")) return args[1];
                    throw new AssertionError("collection should not mutate account preferences");
                });
        BiliClient client = new BiliClient(preferences, url -> {
            requests[0]++;
            equal("api.bilibili.com", url.getHost(), "collection request remains on API host");
            equal("/x/polymer/web-space/seasons_archives_list", url.getPath(), "real collection endpoint used");
            Map<String, String> params = query(url);
            equal("77", params.get("mid"), "view owner is fallback collection owner");
            equal("42", params.get("season_id"), "collection ID sent");
            equal("30", params.get("page_size"), "bounded API page size");
            equal("false", params.get("sort_reverse"), "preserve API default ordering");
            int number = Integer.parseInt(params.get("page_num"));
            equal(requests[0], number, "request pages sequentially");
            JSONObject data;
            try { data = response(number == 1 ? rows(1, 30) : rows(31, 35), 35); }
            catch (Exception exception) { throw new IOException(exception); }
            final byte[] bytes;
            try { bytes = new JSONObject().put("code", 0).put("data", data).toString().getBytes(StandardCharsets.UTF_8); }
            catch (Exception exception) { throw new IOException(exception); }
            return new HttpURLConnection(url) {
                public int getResponseCode() { return 200; }
                public Map<String, List<String>> getHeaderFields() { return Collections.emptyMap(); }
                public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
                public void connect() { }
                public boolean usingProxy() { return false; }
                public void disconnect() { disconnected[0]++; }
            };
        });
        JSONObject expanded = BiliCollections.expand(client, view);
        equal(2, requests[0], "do not stop at the initial 30 entries");
        equal(2, disconnected[0], "release each HTTP response");
        equal(35, expanded.getInt("count"), "second page fully appended");
        check(expanded.getBoolean("complete"), "declared 35 archives verified");
        equal(bv(35), expanded.getJSONArray("entries").getJSONObject(34).getString("bvid"), "last chapter is reachable");
    }

    private static void repeatedPagesAndPartialFailure() throws Exception {
        int[] requests = {0};
        JSONObject view = seed(1).put("ugc_season", season(90, new JSONArray().put(episode(1, 11))));
        JSONObject expanded = BiliCollections.expand(view, (params, remaining) -> {
            requests[0]++;
            return response(rows(1, 30), 90);
        }, () -> 0);
        equal(2, requests[0], "repeated provider page stops immediately");
        equal(30, expanded.getInt("count"), "partial rows retained without duplicate entries");
        equal(90, expanded.getInt("total"), "partial response keeps declared total visible");
        check(!expanded.getBoolean("complete"), "repeated page never masquerades as complete");

        requests[0] = 0;
        expanded = BiliCollections.expand(view, (params, remaining) -> {
            if (++requests[0] == 2) throw new IOException("synthetic provider failure");
            return response(rows(1, 30), 90);
        }, () -> 0);
        equal(30, expanded.getInt("count"), "second-page failure does not discard playable chapters");
        check(!expanded.getBoolean("complete"), "network failure returns explicit partial status");

        expanded = BiliCollections.expand(view, (params, remaining) -> response(new JSONArray(), 90), () -> 0);
        equal(1, expanded.getInt("count"), "empty page retains original seed");
        check(!expanded.getBoolean("complete"), "empty page before total does not fake success");
    }

    private static void unknownTotalsAndInvalidRows() throws Exception {
        JSONObject view = seed(1).put("ugc_season", season(0, new JSONArray().put(episode(1, 11))));
        JSONObject expanded = BiliCollections.expand(view, (params, remaining) -> response(rows(1, 2), 2), () -> 0);
        equal(2, expanded.getInt("total"), "pagination can establish missing inline total");
        check(expanded.getBoolean("complete"), "pagination total proves completion");

        expanded = BiliCollections.expand(view, (params, remaining) -> new JSONObject().put("archives", rows(1, 1)), () -> 0);
        check(expanded.isNull("total"), "unknown total is not fabricated from observed count");
        check(!expanded.getBoolean("complete"), "observed entries alone do not prove completeness");

        JSONObject malformed = seed(1).put("pages", new JSONArray().put(part(11, "第一章")).put(new JSONObject().put("part", "未知章节")))
                .put("ugc_season", season(1, new JSONArray().put(episode(1, 11))));
        expanded = BiliCollections.expand(null, malformed);
        check(!expanded.getBoolean("complete"), "unaddressable multi-P chapter prevents a complete claim");

        view = seed(1).put("ugc_season", season(2, new JSONArray().put(episode(1, 11))));
        expanded = BiliCollections.expand(view, (params, remaining) -> response(new JSONArray()
                .put(episode(1, 11)).put(new JSONObject().put("bvid", "not-a-bv")), 2), () -> 0);
        equal(1, expanded.getInt("archive_count"), "invalid API entry cannot inflate completeness count");
        check(!expanded.getBoolean("complete"), "invalid entry remains partial");
    }

    private static void budgetAndCancellation() throws Exception {
        JSONObject view = seed(1).put("ugc_season", season(100, new JSONArray().put(episode(1, 11))));
        long[] now = {0};
        int[] requests = {0};
        JSONObject expanded = BiliCollections.expand(view, (params, remaining) -> {
            check(remaining > 0 && remaining <= TimeUnit.SECONDS.toNanos(8), "remaining budget passed into transport");
            int number = ++requests[0];
            now[0] += TimeUnit.SECONDS.toNanos(3);
            return response(rows(number, number), 100);
        }, () -> now[0]);
        equal(3, requests[0], "no more page requests after elapsed eight-second budget");
        check(!expanded.getBoolean("complete"), "budget exhaustion remains partial");

        requests[0] = 0;
        expanded = BiliCollections.expand(view, (params, remaining) -> {
            int number = ++requests[0];
            return response(rows(number, number), 100);
        }, () -> 0);
        equal(50, requests[0], "hard page ceiling prevents endless provider pagination");
        check(!expanded.getBoolean("complete"), "page ceiling does not imply completeness");

        try {
            expanded = BiliCollections.expand(view, (params, remaining) -> {
                throw new InterruptedException("synthetic cancellation");
            }, () -> 0);
            check(Thread.currentThread().isInterrupted(), "cancellation preserves the interrupt flag");
            check(!expanded.getBoolean("complete"), "cancelled pagination returns partial chapters");
        } finally { Thread.interrupted(); }
    }

    private static JSONObject seed(int number) throws Exception {
        return new JSONObject().put("bvid", bv(number)).put("cid", number * 10 + 1).put("title", "点击的视频")
                .put("pic", "https://example.test/episode.jpg").put("owner", new JSONObject().put("mid", 77));
    }

    private static JSONObject season(int total, JSONArray... episodeGroups) throws Exception {
        JSONArray sections = new JSONArray();
        for (JSONArray episodes : episodeGroups) sections.put(new JSONObject().put("episodes", episodes));
        return new JSONObject().put("id", 42).put("mid", 88).put("title", "合集真实标题")
                .put("cover", "https://example.test/collection.jpg").put("intro", "合集真实简介")
                .put("ep_count", total).put("sections", sections);
    }

    private static JSONObject episode(int number, long cid) throws Exception {
        return new JSONObject().put("bvid", bv(number)).put("cid", cid).put("title", number == 1 ? "点击的视频" : "第" + number + "章");
    }

    private static JSONObject part(long cid, String title) throws Exception { return new JSONObject().put("cid", cid).put("part", title); }

    private static JSONArray rows(int start, int end) throws Exception {
        JSONArray result = new JSONArray();
        for (int number = start; number <= end; number++) result.put(episode(number, number * 10 + 1));
        return result;
    }

    private static JSONObject response(JSONArray rows, int total) throws Exception {
        return new JSONObject().put("archives", rows).put("page", new JSONObject().put("total", total).put("page_size", 30));
    }

    private static String bv(int number) { return "BV" + String.format(Locale.ROOT, "%010d", number); }

    private static Map<String, String> query(URL url) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : url.getQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], "UTF-8"), URLDecoder.decode(parts[1], "UTF-8"));
        }
        return result;
    }

    private static void equal(Object expected, Object actual, String message) {
        check(expected == null ? actual == null : expected.equals(actual), message + " expected=" + expected + " actual=" + actual);
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
