package com.github.catvod.spider.bili;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** Offline checks for actual upstream parameter values and merged-index ordering. */
public final class PgcFiltersTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        publicDefinitions();
        upstreamParams();
        independentFilterSnapshots();
        mergeOrdering();
        System.out.println("PgcFiltersTest: " + assertions + " assertions passed");
    }

    private static void publicDefinitions() throws Exception {
        JSONArray all = PgcFilters.definitions(true);
        equal(3, all.length(), "all-types page exposes its real filters");
        equal("type", all.getJSONObject(0).getString("key"), "type selector key");
        JSONArray types = all.getJSONObject(0).getJSONArray("value");
        equal(7, types.length(), "all plus six supported types");
        equal("all", types.getJSONObject(0).getString("v"), "default means all types");
        String[] expectedTypes = {"2", "7", "3", "4", "5", "1"};
        for (int i = 0; i < expectedTypes.length; i++)
            equal(expectedTypes[i], types.getJSONObject(i + 1).getString("v"), "Bilibili season type");
        JSONArray category = PgcFilters.definitions(false);
        equal(2, category.length(), "specific type does not duplicate its type selector");
        equal("order", category.getJSONObject(0).getString("key"), "sort exposed on category");
        equal("2", category.getJSONObject(0).getJSONArray("value").getJSONObject(0).getString("v"), "default is most viewed");
        equal("4,6", category.getJSONObject(1).getJSONArray("value").getJSONObject(2).getString("v"), "official membership parameter");
        check(!all.toString().contains("播放量"), "no unsupported exact view-count range is advertised");
    }

    private static void upstreamParams() {
        Map<String, String> selected = new HashMap<>();
        selected.put("order", "4"); selected.put("season_status", "4,6");
        selected.put("season_type", "7"); selected.put("pagesize", "10000");
        selected.put("sort", "1"); selected.put("views", "100000");
        Map<String, String> actual = PgcFilters.params("2", 3, selected);
        equal("2", actual.get("season_type"), "explicit category wins over stale type extension");
        equal("1", actual.get("type"), "official result endpoint type");
        equal("3", actual.get("page"), "requested upstream page retained");
        equal("20", actual.get("pagesize"), "bounded page size");
        equal("4", actual.get("order"), "selected rating sent upstream");
        equal("4,6", actual.get("season_status"), "selected membership sent upstream");
        equal("0", actual.get("sort"), "rating remains descending");
        equal(7, actual.size(), "unsupported parameters never leak upstream");
        equal("10000", selected.get("pagesize"), "caller map unchanged");
        equal("1", PgcFilters.params("1", -9, null).get("page"), "page clamped at first page");
        equal("2", PgcFilters.params("1", 1, null).get("order"), "default supported across all six indexes");
        for (String type : new String[] {"2", "7", "3", "4", "5", "1"})
            equal(type, PgcFilters.params(type, 1, null).get("season_type"), "all six official types accepted");
        for (String type : new String[] {null, "all", "0", "6", "2&order=4"}) {
            boolean rejected = false;
            try { PgcFilters.params(type, 1, null); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "invalid or aggregate type is not sent upstream");
        }
    }

    private static void independentFilterSnapshots() {
        Map<String, String> selected = new HashMap<>();
        equal(PgcFilters.key(null), PgcFilters.key(selected), "empty and absent filters reuse one default snapshot");
        selected.put("order", "3"); selected.put("season_status", "2,6");
        equal(PgcFilters.key(null), PgcFilters.key(selected), "unsupported filters normalize to shared defaults");
        selected.put("order", "0");
        check(!PgcFilters.key(null).equals(PgcFilters.key(selected)), "changed order has independent snapshot");
        String update = PgcFilters.key(selected);
        selected.put("season_status", "1");
        check(!update.equals(PgcFilters.key(selected)), "changed payment filter has independent snapshot");
        selected.put("order", " 0 "); selected.put("season_status", " 1 ");
        equal("0:1", PgcFilters.key(selected), "cache and upstream share normalized values");
    }

    private static void mergeOrdering() throws Exception {
        JSONArray plays = new JSONArray().put(card("unknown", "暂无"))
                .put(card("low", "9999次播放")).put(card("ten_thousand", "1万次播放"))
                .put(card("high", "1.6亿次播放")).put(card("tie", "10000次播放"))
                .put(card("wrong_metric", "999万追番")).put(card("zero", "0次播放"));
        String before = plays.toString();
        equal("high,ten_thousand,tie,low,zero,unknown,wrong_metric", ids(PgcFilters.sort(plays, "2")),
                "mixed units rank together, ties stay stable and missing values stay unknown");
        equal(before, plays.toString(), "sorting does not reorder the caller snapshot");
        equal(ids(PgcFilters.sort(plays, "2")), ids(PgcFilters.sort(plays, null)), "missing selected order defaults to most played");
        JSONArray scores = new JSONArray().put(card("wrong_metric", "999万次播放"))
                .put(card("nine", "9.0分")).put(card("best", "9.9分"))
                .put(card("impossible", "11分")).put(card("tie", "9分"));
        equal("best,nine,tie,wrong_metric,impossible", ids(PgcFilters.sort(scores, "4")),
                "score values cannot be confused with play counts");
        JSONArray dates = new JSONArray().put(card("a", "12月31日更新"))
                .put(card("b", "1月1日更新"));
        equal("a,b", ids(PgcFilters.sort(dates, "0")), "yearless update labels never create guessed global timestamps");
        equal(0, PgcFilters.sort(null, "2").length(), "empty snapshot supported");
    }

    private static JSONObject card(String id, String rank) throws Exception {
        return new JSONObject().put("vod_id", id).put("_pgc_order", rank);
    }

    private static String ids(JSONArray rows) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < rows.length(); i++) {
            if (i > 0) ids.append(',');
            ids.append(rows.getJSONObject(i).getString("vod_id"));
        }
        return ids.toString();
    }

    private static void equal(Object expected, Object actual, String message) {
        check(expected.equals(actual), message + " expected=" + expected + " actual=" + actual);
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
