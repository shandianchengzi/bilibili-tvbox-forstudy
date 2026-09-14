package com.github.catvod.spider.bili;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Expands a video's real UP-created collection without replacing ordinary videos. */
public final class BiliCollections {
    private static final String ENDPOINT = "/x/polymer/web-space/seasons_archives_list";
    private static final long BUDGET_NANOS = TimeUnit.SECONDS.toNanos(8);
    private static final int MAX_PAGES = 50;
    private static final int PAGE_SIZE = 30;
    // HttpURLConnection may finish after interruption. Bound both outstanding work and callers' wait.
    private static final ThreadPoolExecutor NETWORK = new ThreadPoolExecutor(0, 2, 30,
            TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), runnable -> {
                Thread thread = new Thread(runnable, "bili-collection-page");
                thread.setDaemon(true);
                return thread;
            });

    private BiliCollections() { }

    /**
     * count is the number of playable entries, archive_count counts unique BV videos,
     * and total is the provider's archive count (null when unknown).
     * A partial result is usable, but must not be presented as a complete collection.
     */
    public static JSONObject expand(BiliClient client, JSONObject view) throws Exception {
        return expand(view, (params, remaining) -> {
            if (client == null) throw new IllegalStateException("缺少合集接口");
            Future<JSONObject> pending = NETWORK.submit(() -> client.get(ENDPOINT, params));
            try { return pending.get(remaining, TimeUnit.NANOSECONDS); }
            finally { if (!pending.isDone()) pending.cancel(true); }
        }, System::nanoTime);
    }

    interface PageLoader {
        JSONObject load(Map<String, String> params, long remainingNanos) throws Exception;
    }

    interface Clock { long nanoTime(); }

    /** Deterministic API seam: fixtures exercise the same merge and completeness decisions. */
    static JSONObject expand(JSONObject view, PageLoader loader, Clock clock) throws Exception {
        if (view == null) return null;
        JSONObject season = view.optJSONObject("ugc_season");
        if (season == null || positive(season, "id") == 0) return null;
        long started = clock.nanoTime();
        Entries output = new Entries(view);
        JSONArray sections = season.optJSONArray("sections");
        if (sections != null) for (int i = 0; i < sections.length(); i++) {
            JSONObject section = sections.optJSONObject(i);
            JSONArray episodes = section == null ? null : section.optJSONArray("episodes");
            if (episodes == null) continue;
            String sectionTitle = sections.length() > 1 ? first(section.optString("title")) : "";
            for (int j = 0; j < episodes.length(); j++)
                output.add(episodes.optJSONObject(j), false, sectionTitle);
        }
        // The clicked video can be omitted by a truncated sections response.
        if (!output.archives.contains(output.seedBvid)) output.add(view, false);

        long total = positive(season, "ep_count");
        boolean complete = total > 0 && output.archives.size() >= total;
        boolean interrupted = false;
        long mid = positive(season, "mid");
        if (mid == 0) mid = positive(view.optJSONObject("owner"), "mid");
        long traversed = 0;
        Set<String> pageSignatures = new LinkedHashSet<>();
        JSONObject meta = null;
        if (!complete && mid > 0) for (int page = 1; page <= MAX_PAGES; page++) {
            long remaining = BUDGET_NANOS - (clock.nanoTime() - started);
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) break;
            Map<String, String> params = new LinkedHashMap<>();
            params.put("mid", String.valueOf(mid));
            params.put("season_id", String.valueOf(positive(season, "id")));
            params.put("page_num", String.valueOf(page));
            params.put("page_size", String.valueOf(PAGE_SIZE));
            params.put("sort_reverse", "false");
            JSONObject data;
            try { data = loader.load(params, remaining); }
            catch (InterruptedException exception) { interrupted = true; break; }
            catch (Exception exception) { break; }
            if (data == null) break;
            if (meta == null) meta = data.optJSONObject("meta");
            JSONObject pageInfo = data.optJSONObject("page");
            long declaredTotal = positive(pageInfo, "total");
            // Preserve the larger declaration if the API changes while pages are read.
            total = Math.max(total, declaredTotal);
            JSONArray archives = data.optJSONArray("archives");
            if (archives == null || archives.length() == 0) {
                complete = total > 0 && output.archives.size() >= total;
                break;
            }
            StringBuilder signature = new StringBuilder();
            for (int i = 0; i < archives.length(); i++) {
                JSONObject archive = archives.optJSONObject(i);
                signature.append(bvid(archive)).append('|');
            }
            if (!pageSignatures.add(signature.toString())) break;
            int before = output.archives.size();
            for (int i = 0; i < archives.length(); i++) output.add(archives.optJSONObject(i), true);
            traversed += archives.length();
            complete = total > 0 && output.archives.size() >= total;
            if (complete) break;
            // Missing or repeated rows cannot prove completeness, even at an apparent final page.
            if (total > 0 && traversed >= total) break;
            if (page > 1 && output.archives.size() == before) break;
        }
        if (interrupted) Thread.currentThread().interrupt();
        String name = first(season.optString("title"), text(meta, "name"), view.optString("title"));
        String pic = first(season.optString("cover"), text(meta, "cover"), view.optString("pic"));
        String description = first(season.optString("intro"), season.optString("description"),
                text(meta, "description"));
        JSONArray entries = new JSONArray();
        for (JSONObject item : output.items.values()) entries.put(item);
        return new JSONObject().put("name", name).put("pic", pic).put("description", description)
                .put("entries", entries).put("count", entries.length())
                .put("archive_count", output.archives.size()).put("complete", complete && !output.incompleteParts)
                .put("total", total > 0 ? total : JSONObject.NULL);
    }

    private static final class Entries {
        final JSONObject seed;
        final String seedBvid;
        final LinkedHashMap<String, JSONObject> items = new LinkedHashMap<>();
        final Set<String> archives = new LinkedHashSet<>();
        boolean incompleteParts;

        Entries(JSONObject seed) { this.seed = seed; seedBvid = bvid(seed); }

        void add(JSONObject episode, boolean onlyMissingArchive) throws Exception {
            add(episode, onlyMissingArchive, "");
        }

        void add(JSONObject episode, boolean onlyMissingArchive, String sectionTitle) throws Exception {
            if (episode == null) return;
            String bv = bvid(episode);
            if (bv.isEmpty() || onlyMissingArchive && archives.contains(bv)) return;
            JSONObject arc = episode.optJSONObject("arc");
            String title = first(episode.optString("title"), text(arc, "title"), bv);
            if (!sectionTitle.isEmpty()) title = sectionTitle + " · " + title;
            JSONArray pages = episode.optJSONArray("pages");
            if (pages == null || pages.length() == 0) pages = arc == null ? null : arc.optJSONArray("pages");
            long declaredParts = Math.max(positive(episode, "videos"), positive(arc, "videos"));
            if (bv.equals(seedBvid)) {
                declaredParts = Math.max(declaredParts, positive(seed, "videos"));
                JSONArray seedPages = seed.optJSONArray("pages");
                if (seedPages != null && seedPages.length() > 0) pages = seedPages;
            }
            if (declaredParts > Math.max(1, pages == null ? 0 : pages.length())) incompleteParts = true;
            if (pages != null && pages.length() > 0) {
                for (int i = 0; i < pages.length(); i++) {
                    JSONObject page = pages.optJSONObject(i);
                    if (page == null) { incompleteParts = true; continue; }
                    long cid = positive(page, "cid");
                    if (pages.length() > 1 && cid == 0) incompleteParts = true;
                    String part = page.optString("part");
                    String entryTitle = title;
                    if (pages.length() > 1) entryTitle += " · P" + (i + 1)
                            + (part.isEmpty() || part.equals(title) ? "" : " " + part);
                    put(bv, cid, entryTitle);
                }
            } else {
                long cid = positive(episode.optJSONObject("page"), "cid");
                if (cid == 0) cid = positive(episode, "cid");
                if (cid == 0) cid = positive(arc, "cid");
                put(bv, cid, title);
            }
        }

        void put(String bv, long cid, String title) throws Exception {
            if (cid == 0 && archives.contains(bv)) return;
            if (cid > 0) items.remove(bv + ":0");
            String key = bv + ":" + cid;
            if (!items.containsKey(key)) items.put(key, new JSONObject()
                    .put("bvid", bv).put("cid", cid).put("title", title));
            archives.add(bv);
        }
    }

    private static String bvid(JSONObject item) {
        if (item == null) return "";
        String value = first(item.optString("bvid"), text(item.optJSONObject("arc"), "bvid"));
        return value.matches("BV[0-9A-Za-z]{10}") ? value : "";
    }

    private static long positive(JSONObject object, String name) {
        if (object == null) return 0;
        Object value = object.opt(name);
        if (value == null || value == JSONObject.NULL || !String.valueOf(value).matches("[0-9]+")) return 0;
        try { return Math.max(0, Long.parseLong(String.valueOf(value))); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String text(JSONObject object, String name) { return object == null ? "" : object.optString(name); }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty() && !"null".equals(value)) return value;
        return "";
    }
}
