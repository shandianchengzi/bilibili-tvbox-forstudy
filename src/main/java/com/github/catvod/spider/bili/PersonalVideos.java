package com.github.catvod.spider.bili;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Device-local personal-list metadata. No personal data is written to the public catalog. */
public final class PersonalVideos {
    private static final String[] FIELDS = {"duration", "play", "pubdate", "video_review", "favorites"};
    private static final long TTL = 300000L;
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(4, 4, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(80), task -> {
                Thread thread = new Thread(task, "bili-personal-metadata");
                thread.setDaemon(true);
                return thread;
            });
    static { WORKERS.allowCoreThreadTimeOut(true); }
    private final Map<String, JSONObject> cache = new LinkedHashMap<>();
    private long cacheSession = -1;

    public static JSONArray definitions() throws Exception {
        JSONArray filters = VideoFilters.definitions();
        JSONObject order = filters.getJSONObject(0);
        order.put("name", "当前列表排序");
        order.getJSONArray("value").getJSONObject(0).put("n", "原始顺序");
        return filters;
    }

    public static boolean scan(Map<String, String> filters) {
        return Arrays.asList("1", "2", "3", "4").contains(filters.get("duration"))
                || Arrays.asList("10k_100k", "100k_plus", "1k_10k", "lt_1k").contains(filters.get("plays"));
    }

    public static JSONArray favorites(JSONArray source) throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; source != null && i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null || !bv(item.optString("bvid"))) continue;
            JSONObject row = row("video:" + item.optString("bvid"), item.optString("bvid"),
                    item.optString("title"), item.optString("cover"), "收藏视频");
            duration(row, item.opt("duration"));
            exact(row, "pubdate", item.opt("pubtime"));
            JSONObject counts = item.optJSONObject("cnt_info");
            if (counts != null) {
                exact(row, "play", counts.opt("play"));
                exact(row, "video_review", counts.opt("danmaku"));
                exact(row, "favorites", counts.opt("collect"));
            }
            rows.put(row);
        }
        return rows;
    }

    public static JSONArray dynamics(JSONArray source) throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; source != null && i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) continue;
            if (item.optJSONObject("orig") != null) item = item.optJSONObject("orig");
            JSONObject modules = item.optJSONObject("modules");
            JSONObject dynamic = modules == null ? null : modules.optJSONObject("module_dynamic");
            JSONObject major = dynamic == null ? null : dynamic.optJSONObject("major");
            JSONObject archive = major == null ? null : major.optJSONObject("archive");
            if (archive != null && bv(archive.optString("bvid"))) {
                JSONObject row = row("video:" + archive.optString("bvid"), archive.optString("bvid"),
                        archive.optString("title"), archive.optString("cover"), archive.optString("duration_text"));
                duration(row, archive.opt("duration_text"));
                JSONObject stat = archive.optJSONObject("stat");
                if (stat != null) {
                    exact(row, "play", stat.opt("play"));
                    exact(row, "video_review", stat.opt("danmaku"));
                }
                // module_author.pub_ts is the dynamic/repost timestamp, not video publication.
                rows.put(row);
            }
            JSONObject pgc = major == null ? null : major.optJSONObject("pgc");
            if (pgc != null && pgc.optLong("season_id") > 0)
                rows.put(row("season:" + pgc.optLong("season_id"), "", pgc.optString("title"),
                        pgc.optString("cover"), "追番动态"));
        }
        return rows;
    }

    public static JSONArray history(JSONArray source) throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; source != null && i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            JSONObject history = item == null ? null : item.optJSONObject("history");
            if (history == null) continue;
            String bvid = history.optString("bvid");
            String id = bv(bvid) ? "video:" + bvid : history.optLong("epid") > 0 ? "ep:" + history.optLong("epid") : "";
            if (id.isEmpty()) continue;
            JSONObject row = row(id, bv(bvid) ? bvid : "", item.optString("title"), item.optString("cover"),
                    item.optInt("progress") < 0 ? "已看完" : "已观看 " + item.optInt("progress") + " 秒");
            duration(row, item.opt("duration"));
            // view_at is viewing time, never publication time; total is episode count, never plays.
            rows.put(row);
        }
        return rows;
    }

    /** Fetch only statistics needed by active filters, with a shared bounded worker pool and timeout. */
    public JSONObject enrich(BiliClient client, JSONArray source, Map<String, String> filters) throws Exception {
        long session = client.sessionVersion();
        JSONArray rows = new JSONArray(source.toString());
        Set<String> required = required(filters);
        Map<String, List<JSONObject>> pending = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        synchronized (cache) {
            if (cacheSession != session) { cache.clear(); cacheSession = session; }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (!missing(row, required) || !bv(row.optString("bvid"))) continue;
                String bvid = row.optString("bvid");
                JSONObject cached = cache.get(bvid);
                if (cached != null && now - cached.optLong("_cached_at") < TTL) {
                    merge(row, cached);
                    continue;
                }
                List<JSONObject> targets = pending.get(bvid);
                if (targets == null) { targets = new ArrayList<>(); pending.put(bvid, targets); }
                targets.add(row);
            }
        }
        List<String> ids = new ArrayList<>(pending.keySet());
        List<Callable<JSONObject>> tasks = new ArrayList<>();
        AtomicBoolean limited = new AtomicBoolean();
        for (String bvid : ids) tasks.add(() -> {
            if (limited.get() || Thread.currentThread().isInterrupted()) return null;
            try {
                Map<String, String> query = new LinkedHashMap<>();
                query.put("bvid", bvid);
                JSONObject view = client.get("/x/web-interface/wbi/view", query);
                if (!bvid.equals(view.optString("bvid"))) return null;
                JSONObject stats = new JSONObject();
                duration(stats, view.opt("duration"));
                exact(stats, "pubdate", view.opt("pubdate"));
                JSONObject stat = view.optJSONObject("stat");
                if (stat != null) {
                    exact(stats, "play", stat.opt("view"));
                    exact(stats, "video_review", stat.opt("danmaku"));
                    exact(stats, "favorites", stat.opt("favorite"));
                }
                return stats;
            } catch (Exception failure) {
                String message = failure.getMessage();
                if (failure instanceof BiliClient.ApiException) {
                    int code = ((BiliClient.ApiException) failure).code;
                    if (code == -412 || code == -352 || code == -509) limited.set(true);
                } else if (message != null && (message.contains("HTTP 412") || message.contains("HTTP 429"))) limited.set(true);
                return null;
            }
        });
        if (!tasks.isEmpty()) {
            try {
                List<Future<JSONObject>> results = WORKERS.invokeAll(tasks, 8, TimeUnit.SECONDS);
                for (int i = 0; i < results.size(); i++) {
                    Future<JSONObject> result = results.get(i);
                    if (result.isCancelled()) continue;
                    JSONObject stats;
                    try { stats = result.get(); }
                    catch (InterruptedException interrupted) { throw interrupted; }
                    catch (Exception failed) { continue; }
                    if (stats == null) continue;
                    for (JSONObject target : pending.get(ids.get(i))) merge(target, stats);
                    synchronized (cache) {
                        if (client.sessionVersion() == session && cacheSession == session) {
                            if (cache.size() >= 200) cache.remove(cache.keySet().iterator().next());
                            cache.put(ids.get(i), stats.put("_cached_at", System.currentTimeMillis()));
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (java.util.concurrent.RejectedExecutionException busy) {
                // Preserve known metadata and report incompleteness instead of spawning more workers.
            } finally { WORKERS.purge(); }
        }
        if (client.sessionVersion() != session) throw new IllegalStateException("登录状态已变化，请重新打开个人列表");
        boolean incomplete = false;
        for (int i = 0; i < rows.length(); i++) incomplete |= missing(rows.getJSONObject(i), required);
        return new JSONObject().put("list", rows).put("incomplete", incomplete);
    }

    private static Set<String> required(Map<String, String> filters) {
        Set<String> fields = new LinkedHashSet<>();
        String order = filters.get("order");
        if ("click".equals(order)) fields.add("play");
        if ("pubdate".equals(order)) fields.add("pubdate");
        if ("dm".equals(order)) fields.add("video_review");
        if ("stow".equals(order)) fields.add("favorites");
        if (Arrays.asList("1", "2", "3", "4").contains(filters.get("duration"))) fields.add("duration");
        if (Arrays.asList("10k_100k", "100k_plus", "1k_10k", "lt_1k").contains(filters.get("plays"))) fields.add("play");
        return fields;
    }

    private static boolean missing(JSONObject row, Set<String> fields) {
        for (String field : fields) {
            long value = "duration".equals(field) ? VideoFilters.durationSeconds(row.opt(field)) : VideoFilters.exactCount(row.opt(field));
            if (value < 0 || ("pubdate".equals(field) && value == 0)) return true;
        }
        return false;
    }

    private static JSONObject row(String id, String bvid, String title, String pic, String remarks) throws Exception {
        return new JSONObject().put("vod_id", id).put("bvid", bvid).put("title", title).put("pic", pic).put("remarks", remarks);
    }

    private static boolean bv(String value) { return value.matches("BV[0-9A-Za-z]{10}"); }
    private static void exact(JSONObject row, String name, Object value) throws Exception {
        long count = VideoFilters.exactCount(value);
        if (count >= 0 && (!"pubdate".equals(name) || count > 0)) row.put(name, count);
    }
    private static void duration(JSONObject row, Object value) throws Exception {
        long seconds = VideoFilters.durationSeconds(value);
        if (seconds >= 0) row.put("duration", seconds);
    }
    private static void merge(JSONObject row, JSONObject stats) throws Exception {
        for (String field : FIELDS) {
            long known = "duration".equals(field) ? VideoFilters.durationSeconds(row.opt(field)) : VideoFilters.exactCount(row.opt(field));
            if (stats.has(field) && (known < 0 || ("pubdate".equals(field) && known == 0)))
                row.put(field, stats.get(field));
        }
    }
}
