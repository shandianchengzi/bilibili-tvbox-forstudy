package com.github.catvod.spider.bili;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/** Meaningful boundaries for metadata filters, including missing and abbreviated public counts. */
public final class VideoFiltersTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        definitions();
        exactCounts();
        playBoundaries();
        durationBoundaries();
        sortingAndPagination();
        System.out.println("VideoFiltersTest: " + assertions + " assertions passed");
    }

    private static void definitions() throws Exception {
        JSONArray definitions = VideoFilters.definitions();
        equal(3, definitions.length(), "all-video tabs expose three filter groups");
        equal("order", definitions.getJSONObject(0).getString("key"), "sorting first");
        equal("duration", definitions.getJSONObject(1).getString("key"), "duration second");
        JSONObject plays = definitions.getJSONObject(2);
        equal("plays", plays.getString("key"), "play range is independently appendable");
        equal(VideoFilters.playFilter().toString(), plays.toString(), "shared range definition");
        equal("all,10k_100k,100k_plus,1k_10k,lt_1k", values(plays), "specified user option order");
        equal("0,4,3,2,1", values(definitions.getJSONObject(1)), "existing duration options retained");
        equal("totalrank,click,pubdate,dm,stow", values(definitions.getJSONObject(0)), "existing sort options retained");
    }

    private static void exactCounts() {
        equal(0L, VideoFilters.exactCount(0), "real zero remains known");
        equal(1000L, VideoFilters.exactCount(" 1000 "), "trim integer strings");
        equal(9007199254740993L, VideoFilters.exactCount("9007199254740993"), "avoid double precision loss");
        equal(Long.MAX_VALUE, VideoFilters.exactCount(BigInteger.valueOf(Long.MAX_VALUE)), "largest representable count");
        for (Object value : new Object[] {null, JSONObject.NULL, -1, "-1", "+1", "1w", "1万", "1.0", 1.0,
                1.5, true, "", "--", "100,000", "1e5", "9223372036854775808", new JSONObject()}) {
            equal(-1L, VideoFilters.exactCount(value), "reject unknown or inexact count " + value);
        }
    }

    private static void playBoundaries() throws Exception {
        JSONArray raw = new JSONArray();
        for (long count : new long[] {0, 999, 1000, 9999, 10000, 99999, 100000, Long.MAX_VALUE}) {
            raw.put(row(String.valueOf(count), count, "10:00"));
        }
        raw.put(row("missing", null, "10:00")).put(row("abbreviated", "10万", "10:00"));
        equal("0,999", ids(filtered(raw, "plays", "lt_1k")), "lower range excludes unknown counts");
        equal("1000,9999", ids(filtered(raw, "plays", "1k_10k")), "one-thousand boundary");
        equal("10000,99999", ids(filtered(raw, "plays", "10k_100k")), "ten-thousand boundary");
        equal("100000," + Long.MAX_VALUE, ids(filtered(raw, "plays", "100k_plus")), "one-hundred-thousand boundary");
        equal(raw.length(), filtered(raw, "plays", "all").length(), "no range retains unknown counts");
        equal(raw.length(), filtered(raw, "plays", "invalid").length(), "invalid range falls back to all");
        equal(raw.toString(), VideoFilters.apply(raw, null, false).toString(), "null filters preserve metadata");
        equal(0, VideoFilters.apply(null, null, true).length(), "missing catalog is empty");
    }

    private static void durationBoundaries() throws Exception {
        JSONArray raw = new JSONArray();
        for (Object duration : new Object[] {0, "09:59", "10:00", 1799, "30:00", "59:59", "1:00:00", "125:30"}) {
            raw.put(row(String.valueOf(duration), 1234, duration));
        }
        raw.put(row("missing", 1234, null));
        for (Object duration : new Object[] {"1:60", "1:60:00", "1:00:60", "1:2:3:4", "1:", "-1", -1,
                "abc", "9223372036854775807:59", "1.5", 1.5, true}) {
            raw.put(row("invalid", 1234, duration));
        }
        equal("0,09:59", ids(filtered(raw, "duration", "1")), "short duration excludes missing or malformed values");
        equal("10:00,1799", ids(filtered(raw, "duration", "2")), "600 seconds begins second range");
        equal("30:00,59:59", ids(filtered(raw, "duration", "3")), "1800 seconds begins third range");
        equal("1:00:00,125:30", ids(filtered(raw, "duration", "4")), "3600 seconds begins final range, long MM:SS accepted");
        equal(raw.length(), filtered(raw, "duration", "0").length(), "inactive duration retains unknown values");
        equal(raw.length(), filtered(raw, "duration", "wrong").length(), "invalid duration falls back to all");
    }

    private static void sortingAndPagination() throws Exception {
        JSONArray raw = new JSONArray()
                .put(row("a", 1000, "10:00").put("pubdate", 5).put("video_review", 3).put("favorites", 8))
                .put(row("b", 2000, "30:00").put("pubdate", 8).put("video_review", 5).put("favorites", 3))
                .put(row("c", 2000, "10:00").put("pubdate", 8).put("video_review", 4).put("favorites", 5))
                .put(row("d", null, null))
                .put(row("e", "1万", "10:00"))
                .put(row("f", 0, "10:00").put("pubdate", 0).put("video_review", 0).put("favorites", 0));
        String before = raw.toString();
        equal("b,c,a,f,d,e", sortedIds(raw, "click"), "descending play count, stable ties, unknown last");
        equal("b,c,a,f,d,e", sortedIds(raw, "pubdate"), "publication sorting preserves equal timestamps");
        equal("b,c,a,f,d,e", sortedIds(raw, "dm"), "danmaku uses video_review, not comment count");
        equal("a,c,b,f,d,e", sortedIds(raw, "stow"), "favorites sorting uses favorites");
        equal("a,b,c,d,e,f", sortedIds(raw, "totalrank"), "comprehensive sorting keeps upstream order");
        equal("a,b,c,d,e,f", sortedIds(raw, "invalid"), "unknown sort keeps upstream order");
        equal("a,b,c,d,e,f", ids(filtered(raw, "order", "click")), "API-sorted rows do not get locally reordered");
        Map<String, String> combined = filters("duration", "2");
        combined.put("plays", "1k_10k");
        combined.put("order", "click");
        JSONArray selected = VideoFilters.apply(raw, combined, true);
        equal("c,a", ids(selected), "duration and play count combine before sorting");
        JSONObject first = Recommendations.page(selected, 1, 1), second = Recommendations.page(selected, 2, 1);
        equal("c", ids(first.getJSONArray("list")), "first page after filtering");
        equal("a", ids(second.getJSONArray("list")), "second page contains remaining match");
        equal(2, first.getInt("total"), "pagination total counts matches");
        equal(2, first.getInt("pagecount"), "pagination page count counts matches");
        equal(before, raw.toString(), "input objects and array are not mutated");
        equal(3, combined.size(), "caller filter map is not mutated");
        equal("c", selected.getJSONObject(0).getString("id"), "original metadata survives selection");
        equal(4, selected.getJSONObject(0).getInt("video_review"), "unrelated metadata survives selection");
    }

    private static JSONObject row(String id, Object play, Object duration) throws Exception {
        return new JSONObject().put("id", id).put("play", play).put("duration", duration);
    }

    private static Map<String, String> filters(String key, String value) {
        Map<String, String> result = new HashMap<>();
        result.put(key, value);
        return result;
    }

    private static JSONArray filtered(JSONArray rows, String key, String value) {
        return VideoFilters.apply(rows, filters(key, value), false);
    }

    private static String sortedIds(JSONArray rows, String order) throws Exception {
        return ids(VideoFilters.apply(rows, filters("order", order), true));
    }

    private static String ids(JSONArray rows) throws Exception {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < rows.length(); i++) {
            if (i > 0) result.append(',');
            result.append(rows.getJSONObject(i).getString("id"));
        }
        return result.toString();
    }

    private static String values(JSONObject definition) throws Exception {
        StringBuilder result = new StringBuilder();
        JSONArray values = definition.getJSONArray("value");
        for (int i = 0; i < values.length(); i++) {
            if (i > 0) result.append(',');
            result.append(values.getJSONObject(i).getString("v"));
        }
        return result.toString();
    }

    private static void equal(Object expected, Object actual, String message) {
        assertions++;
        if (!expected.equals(actual)) throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
    }
}
