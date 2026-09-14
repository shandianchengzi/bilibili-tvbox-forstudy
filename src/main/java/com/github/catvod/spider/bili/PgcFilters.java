package com.github.catvod.spider.bili;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Filters shared by the six official Bilibili film/series indexes. */
public final class PgcFilters {
    private static final String[] TYPES = {"2", "7", "3", "4", "5", "1"};
    private static final String[] TYPE_NAMES = {"电影", "综艺", "纪录片", "国创", "剧集", "番剧"};
    private static final Pattern PLAYS = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)(万|亿)?次播放$");
    private static final Pattern SCORE = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)分$");

    private PgcFilters() { }

    public static JSONArray definitions(boolean includeType) throws Exception {
        JSONArray definitions = new JSONArray();
        if (includeType) {
            JSONArray types = new JSONArray().put(value("全部", "all"));
            for (int i = 0; i < TYPES.length; i++) types.put(value(TYPE_NAMES[i], TYPES[i]));
            definitions.put(definition("type", "类型", types));
        }
        definitions.put(definition("order", "排序", new JSONArray()
                .put(value("最多播放", "2"))
                .put(value("最近更新", "0"))
                .put(value("最高评分", "4"))));
        definitions.put(definition("season_status", "付费", new JSONArray()
                .put(value("全部", "-1"))
                .put(value("免费", "1"))
                .put(value("大会员", "4,6"))));
        return definitions;
    }

    /** Release-date ordering belongs to the movie index, not every PGC type. */
    public static JSONArray forType(String type) throws Exception {
        JSONArray filters = definitions(false);
        if ("2".equals(type)) {
            for (int i = 0; i < filters.length(); i++) {
                JSONObject filter = filters.getJSONObject(i);
                if ("order".equals(filter.optString("key")))
                    filter.getJSONArray("value").put(value("最近上映", "6"));
            }
        }
        return filters;
    }

    /** Only values supported by every official index are forwarded to Bilibili. */
    public static Map<String, String> params(String type, int page, Map<String, String> selected) {
        if (!Arrays.asList(TYPES).contains(type)) throw new IllegalArgumentException("未知影视类型");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("season_type", type);
        params.put("type", "1");
        params.put("page", String.valueOf(Math.max(1, page)));
        params.put("pagesize", "20");
        params.put("order", "2".equals(type) && selected != null && "6".equals(selected.get("order")) ? "6" : order(selected));
        params.put("sort", "0");
        params.put("season_status", status(selected));
        return params;
    }

    /** Canonical key: equivalent defaults share a snapshot; changed filters do not. */
    public static String key(Map<String, String> selected) {
        return order(selected) + ":" + status(selected);
    }

    /**
     * Rank the merged snapshot using the API's displayed count or score. Rounded
     * counts are suitable for stable presentation, never exact range filtering.
     * Update labels omit the year (e.g. "8月13日更新"), so order=0 deliberately
     * keeps the upstream ordering within each interleaved category.
     */
    public static JSONArray sort(JSONArray cards, String order) throws Exception {
        List<Object> rows = new ArrayList<>();
        for (int i = 0; cards != null && i < cards.length(); i++) rows.add(cards.get(i));
        String selected = allowed(order, "2", "2", "0", "4");
        if (!"0".equals(selected)) {
            Collections.sort(rows, (left, right) -> {
                BigDecimal a = rank(left, selected), b = rank(right, selected);
                if (a == null) return b == null ? 0 : 1;
                if (b == null) return -1;
                return b.compareTo(a);
            });
        }
        JSONArray out = new JSONArray();
        for (Object row : rows) out.put(row);
        return out;
    }

    private static BigDecimal rank(Object item, String order) {
        if (!(item instanceof JSONObject)) return null;
        String label = ((JSONObject) item).optString("_pgc_order", "").trim();
        if (label.length() > 64) return null;
        Matcher match = ("4".equals(order) ? SCORE : PLAYS).matcher(label);
        if (!match.matches()) return null;
        try {
            BigDecimal number = new BigDecimal(match.group(1));
            if ("4".equals(order)) return number.compareTo(BigDecimal.TEN) <= 0 ? number : null;
            if ("万".equals(match.group(2))) return number.multiply(new BigDecimal("10000"));
            if ("亿".equals(match.group(2))) return number.multiply(new BigDecimal("100000000"));
            return number;
        } catch (NumberFormatException ignored) { return null; }
    }

    private static String order(Map<String, String> selected) {
        return allowed(selected == null ? null : selected.get("order"), "2", "2", "0", "4");
    }

    private static String status(Map<String, String> selected) {
        return allowed(selected == null ? null : selected.get("season_status"), "-1", "-1", "1", "4,6");
    }

    private static String allowed(String value, String fallback, String... choices) {
        String clean = value == null ? "" : value.trim();
        return Arrays.asList(choices).contains(clean) ? clean : fallback;
    }

    private static JSONObject value(String name, String value) throws Exception {
        return new JSONObject().put("n", name).put("v", value);
    }

    private static JSONObject definition(String key, String name, JSONArray values) throws Exception {
        return new JSONObject().put("key", key).put("name", name).put("value", values);
    }
}
