package com.github.catvod.spider.bili;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Offline filtering of public UP-video metadata. Unknown counts are never zero. */
public final class VideoFilters {
    private VideoFilters() { }

    public static JSONArray definitions() throws Exception {
        return new JSONArray()
                .put(definition("order", "排序", new String[][] {
                    {"综合排序", "totalrank"}, {"最多点击", "click"}, {"最新发布", "pubdate"},
                    {"最多弹幕", "dm"}, {"最多收藏", "stow"}}))
                .put(definition("duration", "时长", new String[][] {
                    {"全部", "0"}, {"60分钟以上", "4"}, {"30-60分钟", "3"},
                    {"10-30分钟", "2"}, {"10分钟以下", "1"}}))
                .put(playFilter());
    }

    public static JSONObject playFilter() throws Exception {
        return definition("plays", "播放量", new String[][] {
            {"全部", "all"}, {"1万-10万", "10k_100k"}, {"10万以上", "100k_plus"},
            {"1千-1万", "1k_10k"}, {"1千以下", "lt_1k"}});
    }

    private static JSONObject definition(String key, String name, String[][] options) throws Exception {
        JSONArray values = new JSONArray();
        for (String[] option : options) values.put(new JSONObject().put("n", option[0]).put("v", option[1]));
        return new JSONObject().put("key", key).put("name", name).put("value", values);
    }

    /** Filter before paging; retain upstream relevance order unless local sorting is requested. */
    public static JSONArray apply(JSONArray raw, Map<String, String> filters, boolean sort) {
        String duration = parameter(filters, "duration"), plays = parameter(filters, "plays");
        List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; raw != null && i < raw.length(); i++) {
            JSONObject row = raw.optJSONObject(i);
            if (row != null && durationMatches(row.opt("duration"), duration)
                    && playsMatch(exactCount(row.opt("play")), plays)) rows.add(row);
        }
        final String field = sort ? sortField(parameter(filters, "order")) : "";
        if (!field.isEmpty()) Collections.sort(rows, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject left, JSONObject right) {
                long a = exactCount(left.opt(field)), b = exactCount(right.opt(field));
                return a == b ? 0 : a > b ? -1 : 1;
            }
        });
        JSONArray result = new JSONArray();
        for (JSONObject row : rows) result.put(row);
        return result;
    }

    /** Accept exact integer values only; display abbreviations cannot establish range boundaries. */
    public static long exactCount(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) return -1;
        String digits = String.valueOf(value).trim();
        if (!digits.matches("[0-9]{1,19}")) return -1;
        try { return Long.parseLong(digits); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private static String parameter(Map<String, String> filters, String key) {
        String value = filters == null ? null : filters.get(key);
        return value == null ? "" : value;
    }

    private static String sortField(String order) {
        if ("click".equals(order)) return "play";
        if ("pubdate".equals(order)) return "pubdate";
        if ("dm".equals(order)) return "video_review";
        if ("stow".equals(order)) return "favorites";
        return "";
    }

    private static boolean playsMatch(long count, String range) {
        if ("lt_1k".equals(range)) return count >= 0 && count < 1000;
        if ("1k_10k".equals(range)) return count >= 1000 && count < 10000;
        if ("10k_100k".equals(range)) return count >= 10000 && count < 100000;
        if ("100k_plus".equals(range)) return count >= 100000;
        return true;
    }

    private static boolean durationMatches(Object value, String range) {
        if (!"1".equals(range) && !"2".equals(range) && !"3".equals(range) && !"4".equals(range)) return true;
        long seconds = durationSeconds(value);
        if (seconds < 0) return false;
        if ("1".equals(range)) return seconds < 600;
        if ("2".equals(range)) return seconds >= 600 && seconds < 1800;
        if ("3".equals(range)) return seconds >= 1800 && seconds < 3600;
        return seconds >= 3600;
    }

    private static long durationSeconds(Object value) {
        long integer = exactCount(value);
        if (integer >= 0) return integer;
        if (!(value instanceof String)) return -1;
        String[] parts = ((String) value).trim().split(":", -1);
        if (parts.length < 2 || parts.length > 3) return -1;
        long seconds = 0;
        for (int i = 0; i < parts.length; i++) {
            long part = exactCount(parts[i]);
            if (part < 0 || (i > 0 && part >= 60) || seconds > (Long.MAX_VALUE - part) / 60) return -1;
            seconds = seconds * 60 + part;
        }
        return seconds;
    }
}
