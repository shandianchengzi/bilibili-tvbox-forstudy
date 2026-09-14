package com.github.catvod.spider.bili;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stable, mode-local snapshot aggregation; never implies a complete Bilibili index. */
public final class Recommendations {
    private Recommendations() { }

    /** Keep every configured category, round-robin its items, and deduplicate BV ids. */
    public static JSONArray catalog(JSONObject interests, JSONObject catalog) throws Exception {
        Map<String, JSONArray> byId = new LinkedHashMap<>();
        JSONArray cached = catalog == null ? null : catalog.optJSONArray("categories");
        for (int i = 0; cached != null && i < cached.length(); i++) {
            JSONObject category = cached.optJSONObject(i);
            if (category != null) byId.put(category.optString("id"), category.optJSONArray("items"));
        }
        List<JSONArray> groups = new ArrayList<>();
        JSONArray configured = interests == null ? null : interests.optJSONArray("categories");
        for (int i = 0; configured != null && i < configured.length(); i++) {
            JSONObject category = configured.optJSONObject(i);
            if (category != null) groups.add(byId.get(category.optString("id")));
        }
        JSONArray merged = interleave(groups, "bvid"), valid = new JSONArray();
        for (int i = 0; i < merged.length(); i++) {
            JSONObject video = merged.getJSONObject(i);
            if (video.optString("bvid").matches("BV[0-9A-Za-z]{10}")) valid.put(video);
        }
        return valid;
    }

    /** Input order is retained inside each category; the first duplicate wins. */
    public static JSONArray interleave(List<JSONArray> groups, String idKey) throws Exception {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        int longest = 0;
        for (JSONArray group : groups) if (group != null) longest = Math.max(longest, group.length());
        for (int row = 0; row < longest; row++) {
            for (JSONArray group : groups) {
                JSONObject item = group == null ? null : group.optJSONObject(row);
                if (item == null) continue;
                String id = item.optString(idKey);
                if (!id.isEmpty() && seen.add(id)) out.put(item);
            }
        }
        return out;
    }

    public static JSONObject page(JSONArray all, int page, int pageSize) throws Exception {
        int p = Math.max(1, page), size = Math.max(1, pageSize);
        JSONArray rows = new JSONArray();
        long offset = (long) (p - 1) * size;
        for (long i = offset; i < Math.min((long) all.length(), offset + size); i++) rows.put(all.get((int) i));
        return new JSONObject().put("list", rows).put("page", p)
                .put("pagecount", Math.max(1, (all.length() + size - 1) / size))
                .put("limit", size).put("total", all.length());
    }
}
