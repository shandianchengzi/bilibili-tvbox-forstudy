package com.github.catvod.spider.bili;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/** Bounded page windows for filters that the upstream API cannot apply. */
public final class FilteredPages {
    private static final int UPSTREAM_LIMIT = 20;
    private static final int SCAN_PAGES = 3;

    private FilteredPages() { }

    public interface PageLoader {
        JSONObject load(int upstreamPage) throws Exception;
    }

    /** Each logical page owns a fixed window, so sparse matches cannot repeat upstream pages. */
    public static JSONObject load(int logicalPage, boolean scan, PageLoader loader) throws Exception {
        int page = Math.max(1, logicalPage), count = scan ? SCAN_PAGES : 1;
        int limit = count * UPSTREAM_LIMIT;
        long start = (long) (page - 1) * count + 1;
        if (start > Integer.MAX_VALUE) throw new IllegalArgumentException("页码超出支持范围");
        if (loader == null) throw new IllegalArgumentException("缺少分页加载器");
        JSONArray rows = new JSONArray();
        Set<String> seen = new HashSet<>();
        boolean more = false;
        for (int offset = 0; offset < count; offset++) {
            long upstream = start + offset;
            if (upstream > Integer.MAX_VALUE) { more = false; break; }
            JSONObject response = loader.load((int) upstream);
            JSONArray items = response == null ? null : response.optJSONArray("list");
            if (items == null) throw new IllegalStateException("视频列表响应缺少 list");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String id = item.optString("vod_id", "");
                if ((id.isEmpty() || seen.add(id)) && rows.length() < limit) rows.put(item);
            }
            more = VideoFilters.exactCount(response.opt("pagecount")) > upstream
                    && upstream < Integer.MAX_VALUE && page < Integer.MAX_VALUE;
            if (!more) break;
        }
        // An empty continuation page causes some TVBox clients to decrement and retry the same page.
        // End this bounded scan explicitly; it makes no claim about other upstream results.
        boolean emptyScan = scan && rows.length() == 0;
        if (emptyScan) more = false;
        int pageCount = more ? page + 1 : page;
        long estimatedTotal = more ? (long) pageCount * limit : (long) (page - 1) * limit + rows.length();
        JSONObject result = new JSONObject().put("list", rows).put("page", page)
                .put("pagecount", pageCount).put("limit", limit)
                .put("total", Math.min(Integer.MAX_VALUE, estimatedTotal));
        if (emptyScan) result.put("msg", "本次检查的搜索结果中没有符合播放量的视频，请调整筛选");
        return result;
    }
}
