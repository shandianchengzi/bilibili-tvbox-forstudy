package com.github.catvod.spider;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.widget.Toast;

import com.github.catvod.crawler.Spider;
import com.github.catvod.spider.bili.BiliClient;
import com.github.catvod.spider.bili.BiliCollections;
import com.github.catvod.spider.bili.PlaybackQuality;
import com.github.catvod.spider.bili.LocalServer;
import com.github.catvod.spider.bili.VideoMetadata;
import com.github.catvod.spider.bili.QrLoginDialog;
import com.github.catvod.spider.bili.QrPoller;
import com.github.catvod.spider.bili.Recommendations;
import com.github.catvod.spider.bili.VideoFilters;
import com.github.catvod.spider.bili.PgcFilters;
import com.github.catvod.spider.bili.FilteredPages;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Original CatVod spider. All account/API traffic originates on the user's device. */
public class BiliStudy extends Spider {
    private static final String DEFAULT_BASE = "https://shandianchengzi.github.io/bilibili-tvbox-forstudy/";
    private static final int PAGE_SIZE = 20;
    private static final String[] PGC_TYPES = {"2", "7", "3", "4", "5", "1"};
    private static final String[] PGC_NAMES = {"电影", "综艺", "纪录片", "国创", "剧集", "番剧"};
    private static final AtomicLong LOGIN_GENERATION = new AtomicLong();
    private static volatile String qrPicture = "";
    private static volatile String qrMessage = "";
    private static volatile long qrCreated;
    private static volatile boolean qrRunning;
    private static volatile Bitmap qrBitmap;
    private static volatile Thread qrWorker;
    private static volatile BiliClient qrClient;
    private static final QrLoginDialog QR_DIALOG = new QrLoginDialog();
    private Context context;
    private BiliClient client;
    private String mode = "media";
    private String catalogUrl = DEFAULT_BASE + "catalog.json";
    private String interestsUrl = DEFAULT_BASE + "interests.json";
    private JSONObject interests;
    private JSONObject catalog;
    private long loadedAt;
    private JSONArray mediaSnapshot;
    private long mediaLoadedAt;
    private long mediaAttemptedAt;
    private String mediaFilterKey = "";
    private final Map<Integer, String> dynamicCursors = new ConcurrentHashMap<>();
    private final Map<Integer, JSONObject> historyCursors = new ConcurrentHashMap<>();

    public void init(Context context, String extend) throws Exception {
        Init.init(context);
        this.context = context.getApplicationContext();
        this.client = new BiliClient(this.context);
        if (extend != null && !extend.trim().isEmpty()) {
            JSONObject config = new JSONObject(extend.trim().startsWith("{") ? extend : readPublic(extend));
            mode = config.optString("mode", "media");
            catalogUrl = config.optString("catalog", catalogUrl);
            interestsUrl = config.optString("interests", interestsUrl);
        }
    }

    public String homeContent(boolean filter) throws Exception {
        JSONArray classes = new JSONArray();
        JSONObject filters = new JSONObject();
        if ("account".equals(mode))
            return new JSONObject().put("class", classes).put("filters", filters).toString();
        classes.put(category("all", "全部"));
        if ("media".equals(mode)) {
            filters.put("all", PgcFilters.definitions(true));
            classes.put(category("dynamic", "动态"));
            classes.put(category("favorites", "收藏夹"));
            classes.put(category("history", "历史记录"));
            for (int i = 0; i < PGC_TYPES.length; i++) {
                classes.put(category("pgc:" + PGC_TYPES[i], PGC_NAMES[i]));
                filters.put("pgc:" + PGC_TYPES[i], PgcFilters.definitions(false));
            }
            JSONArray favoriteFilters = new JSONArray();
            if (hasSession()) {
                try {
                    JSONArray folders = favoriteFolders();
                    JSONArray values = new JSONArray();
                    for (int i = 0; i < folders.length(); i++) {
                        JSONObject f = folders.getJSONObject(i);
                        values.put(new JSONObject().put("n", f.optString("title")).put("v", f.optString("id")));
                    }
                    if (values.length() > 0) favoriteFilters.put(
                        new JSONObject().put("key", "fid").put("name", "收藏夹").put("value", values));
                } catch (Exception ignored) { /* Personal-category requests show the actual error. */ }
            }
            filters.put("favorites", favoriteFilters.put(VideoFilters.playFilter()));
        } else {
            loadCatalog();
            JSONArray categories = interests.optJSONArray("categories");
            JSONArray categoryValues = new JSONArray().put(new JSONObject().put("n", "全部").put("v", "all"));
            for (int i = 0; categories != null && i < categories.length(); i++) {
                JSONObject c = categories.getJSONObject(i);
                String id = "tag:" + c.getString("id");
                classes.put(category(id, c.getString("name")));
                categoryValues.put(new JSONObject().put("n", c.getString("name")).put("v", c.getString("id")));
                JSONArray values = new JSONArray();
                JSONArray queries = c.getJSONArray("queries");
                for (int q = 0; q < queries.length(); q++) values.put(new JSONObject().put("n", queries.getString(q)).put("v", queries.getString(q)));
                filters.put(id, appendFilters(new JSONArray()
                    .put(new JSONObject().put("key", "keyword").put("name", "主题").put("value", values)), VideoFilters.definitions()));
            }
            filters.put("all", appendFilters(new JSONArray().put(new JSONObject().put("key", "category")
                    .put("name", "分类").put("value", categoryValues)), VideoFilters.definitions()));
        }
        return new JSONObject().put("class", classes).put("filters", filters).put("list", new JSONArray()).toString();
    }

    private static JSONArray appendFilters(JSONArray target, JSONArray extra) throws Exception {
        for (int i = 0; i < extra.length(); i++) target.put(extra.get(i));
        return target;
    }

    public String homeVideoContent() throws Exception {
        try {
            if ("account".equals(mode)) return accountCards().toString();
            // A nonempty home list makes compatible clients create a duplicate recommendation tab.
            return new JSONObject().put("list", new JSONArray()).toString();
        } catch (Exception e) { return errorPage(e).toString(); }
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        try {
            int p = Math.max(1, Integer.parseInt(pg));
            Map<String, String> f = extend == null ? new HashMap<String, String>() : extend;
            if ("account".equals(mode)) return "account".equals(tid) ? accountCards().toString() : page(new JSONArray(), p, false).toString();
            if ("account".equals(tid) || (Arrays.asList("dynamic", "favorites", "history").contains(tid) && !hasSession()))
                return accountNotice().toString();
            if ("all".equals(tid)) return all(p, f).toString();
            if (tid.startsWith("pgc:")) return pgc(tid.substring(4), p, f).toString();
            if ("dynamic".equals(tid)) return dynamics(p).toString();
            if ("favorites".equals(tid)) return favorites(p, f).toString();
            if ("history".equals(tid)) return history(p).toString();
            if (tid.startsWith("tag:")) return tagged(tid.substring(4), p, f).toString();
            return page(new JSONArray(), p, false).toString();
        } catch (Exception e) { return errorPage(e).toString(); }
    }

    public String searchContent(String key, boolean quick) throws Exception { return searchContent(key, quick, "1"); }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        try {
            int p = Math.max(1, Integer.parseInt(pg));
            if ("account".equals(mode)) return page(new JSONArray(), p, false).toString();
            Matcher bv = Pattern.compile("BV[0-9A-Za-z]{10}").matcher(key);
            Matcher ep = Pattern.compile("(?:^|/)(ep|ss)([0-9]+)").matcher(key.trim());
            if (bv.find()) return page(new JSONArray().put(card("video:" + bv.group(), bv.group(), "打开视频", "")), 1, false).toString();
            if (ep.find()) return page(new JSONArray().put(card((ep.group(1).equals("ep") ? "ep:" : "season:") + ep.group(2), ep.group(), "打开影视", "")), 1, false).toString();
            if ("media".equals(mode)) return mediaSearch(key.trim(), p).toString();
            String keyword = "zhou_shen".equals(mode) && !key.contains("周深") ? "周深 " + key.trim() : key.trim();
            JSONObject data = client.search(keyword, p, "totalrank");
            return page(searchCards(data.optJSONArray("result")), p, p < data.optInt("numPages", p)).toString();
        } catch (Exception e) { return errorPage(e).toString(); }
    }

    /** Official film/series search is separate from authored-video collections. */
    private JSONObject mediaSearch(String keyword, int p) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "bili-media-search");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Callable<JSONObject>> requests = new ArrayList<>();
            for (String kind : Arrays.asList("media_ft", "media_bangumi"))
                requests.add(() -> client.searchMedia(kind, keyword, p));
            List<Future<JSONObject>> responses = workers.invokeAll(requests, 8, TimeUnit.SECONDS);
            JSONArray result = new JSONArray();
            Set<String> seen = new HashSet<>();
            boolean more = false, succeeded = false;
            for (Future<JSONObject> response : responses) {
                if (response.isCancelled()) continue;
                JSONObject data;
                try { data = response.get(); }
                catch (InterruptedException interrupted) { throw interrupted; }
                catch (Exception failure) { continue; }
                JSONArray rows = data.optJSONArray("result");
                if (rows == null) continue;
                succeeded = true;
                more |= p < data.optInt("numPages", p);
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject item = rows.optJSONObject(i);
                    if (item == null || item.optLong("season_id") <= 0) continue;
                    String season = item.optString("season_id");
                    if (seen.add(season)) result.put(card("season:" + season, item.optString("title"),
                            item.optString("index_show", "Bilibili 官方影视"), item.optString("cover")));
                }
            }
            if (!succeeded) throw new IllegalStateException("影视搜索暂时不可用，请稍后重试");
            return page(result, p, more);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally { workers.shutdownNow(); }
    }

    public String detailContent(List<String> ids) throws Exception {
        try {
            String id = ids.get(0);
            JSONObject vod;
            if (id.startsWith("auth:")) vod = "account".equals(mode) ? authDetail(id.substring(5))
                    : card("notice:请进入首页的 Bilibili 扫码登录 卡片管理账号", "请进入 Bilibili 扫码登录", "统一账号入口", "")
                        .put("vod_content", "返回源选择，进入 Bilibili 扫码登录 卡片；登录状态在全部 Bilibili 模块间共享。");
            else if (id.startsWith("video:")) vod = videoDetail(id.substring(6));
            else if (id.startsWith("season:")) vod = seasonDetail("season_id", id.substring(7));
            else if (id.startsWith("ep:")) vod = seasonDetail("ep_id", id.substring(3));
            else vod = card(id, "提示", "", "").put("vod_content", id.startsWith("notice:") ? id.substring(7) : "无法识别的视频编号");
            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        } catch (Exception e) {
            return new JSONObject().put("list", new JSONArray().put(card("notice:error", "暂时无法加载", "", "").put("vod_content", friendly(e)))).toString();
        }
    }

    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        try {
            if (id.startsWith("noop")) throw new IllegalStateException("扫码成功后重新加载源配置或重启 TVBox，再进入个人分类");
            int quality = PlaybackQuality.requested(id);
            JSONObject data = playData(PlaybackQuality.baseId(id), quality);
            return PlaybackQuality.player(data, quality).toString();
        } catch (Exception e) {
            toast(friendly(e));
            return new JSONObject().put("parse", 0).put("url", "").put("msg", friendly(e)).toString();
        }
    }

    private JSONObject playData(String id, int quality) throws Exception {
        String qn = String.valueOf(quality > 0 ? quality : 80);
        if (id.startsWith("playep:")) {
            JSONObject data = client.get("/pgc/player/web/v2/playurl", params("ep_id", digits(id.substring(7)),
                    "qn", qn, "fnval", "4048", "fnver", "0", "fourk", "1"));
            return data.has("video_info") ? data.getJSONObject("video_info") : data;
        }
        String[] parts = id.split(":", -1);
        if (parts.length < 2 || parts.length > 3 || !parts[0].equals("play"))
            throw new IllegalArgumentException("播放编号无效");
        String bvid = checkedBvid(parts[1]);
        String cid = parts.length > 2 && !parts[2].equals("0") ? digits(parts[2])
                : client.get("/x/web-interface/wbi/view", params("bvid", bvid)).getString("cid");
        return client.get("/x/player/wbi/playurl", params("bvid", bvid, "cid", cid,
                "qn", qn, "fnval", "4048", "fnver", "0", "fourk", "1"));
    }

    /** A single probe populates source-line choices without persisting signed media URLs. */
    private JSONObject qualityChoices(JSONObject vod) throws Exception {
        String firstLine = vod.optString("vod_play_url").split("\\$\\$\\$", -1)[0];
        String firstEpisode = firstLine.split("#", -1)[0];
        int delimiter = firstEpisode.indexOf('$');
        if (delimiter < 0) return vod;
        try {
            JSONObject data = playData(firstEpisode.substring(delimiter + 1), PlaybackQuality.MAX_REQUEST);
            PlaybackQuality.addChoices(vod, data);
        } catch (Exception ignored) {
            // A failed quality probe must not hide descriptions or the original episode list.
            PlaybackQuality.addChoices(vod, null);
            vod.put("vod_content", vod.optString("vod_content")
                    + "\n\n暂时无法获取可选清晰度，仍可直接播放；需要手动选画质时请重新进入详情页。");
        }
        return vod;
    }

    public boolean isVideoFormat(String url) { return url != null && (url.contains(".mpd") || url.contains(".m4s") || url.contains(".mp4")); }
    public boolean manualVideoCheck() { return false; }

    private JSONObject pgc(String type, int p, Map<String, String> filters) throws Exception {
        JSONObject data = client.get("/pgc/season/index/result", PgcFilters.params(type, p, filters));
        JSONArray a = data.optJSONArray("list"), out = new JSONArray();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject s = a.getJSONObject(i);
            out.put(card("season:" + digits(s.optString("season_id")), s.optString("title"), s.optString("index_show"), s.optString("cover"))
                    .put("_pgc_order", s.optString("order")));
        }
        return page(out, p, data.optInt("has_next", 0) == 1 || data.optBoolean("has_next", false));
    }

    private JSONObject all(int p, Map<String, String> filters) throws Exception {
        if ("media".equals(mode)) {
            String type = filters.get("type");
            if (Arrays.asList(PGC_TYPES).contains(type)) return pgc(type, p, filters);
            return Recommendations.page(mediaRecommendations(filters), p, PAGE_SIZE);
        }
        loadCatalog();
        JSONObject configured = interests;
        String category = filters.get("category");
        if (category != null && !category.isEmpty() && !"all".equals(category)) {
            JSONArray selected = new JSONArray(), categories = interests.getJSONArray("categories");
            for (int i = 0; i < categories.length(); i++) {
                JSONObject candidate = categories.getJSONObject(i);
                if (category.equals(candidate.optString("id"))) selected.put(candidate);
            }
            configured = new JSONObject().put("categories", selected);
        }
        JSONArray videos = VideoFilters.apply(Recommendations.catalog(configured, catalog), filters, true);
        return Recommendations.page(searchCards(videos), p, PAGE_SIZE);
    }

    /** Six independent PGC queries share one wall-clock budget, keeping home responsive. */
    private synchronized JSONArray mediaRecommendations(Map<String, String> filters) throws Exception {
        String key = PgcFilters.key(filters);
        if (!key.equals(mediaFilterKey)) {
            mediaSnapshot = null;
            mediaLoadedAt = 0;
            mediaAttemptedAt = 0;
            mediaFilterKey = key;
        }
        long now = System.currentTimeMillis();
        if (mediaSnapshot != null && now - mediaLoadedAt < 300000) return mediaSnapshot;
        if (now - mediaAttemptedAt < 30000) {
            if (mediaSnapshot != null && mediaSnapshot.length() > 0) return mediaSnapshot;
            throw new IllegalStateException("影视列表暂时不可用，请稍后刷新或进入电影、综艺等分类");
        }
        mediaAttemptedAt = now;
        ExecutorService workers = Executors.newFixedThreadPool(PGC_TYPES.length, task -> {
            Thread thread = new Thread(task, "bili-media-home");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Callable<JSONArray>> requests = new ArrayList<>();
            for (String type : PGC_TYPES) requests.add(() -> pgc(type, 1, filters).getJSONArray("list"));
            List<Future<JSONArray>> completed = workers.invokeAll(requests, 8, TimeUnit.SECONDS);
            List<JSONArray> groups = new ArrayList<>();
            for (Future<JSONArray> future : completed) {
                try { groups.add(future.isCancelled() ? new JSONArray() : future.get()); }
                catch (Exception ignored) { groups.add(new JSONArray()); }
            }
            JSONArray fresh = PgcFilters.sort(Recommendations.interleave(groups, "vod_id"), filters.get("order"));
            if (fresh.length() == 0) {
                if (mediaSnapshot != null && mediaSnapshot.length() > 0) return mediaSnapshot;
                throw new IllegalStateException("影视列表暂时不可用，请稍后刷新或进入电影、综艺等分类");
            }
            mediaSnapshot = fresh;
            mediaLoadedAt = System.currentTimeMillis();
            return mediaSnapshot;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure;
        } finally { workers.shutdownNow(); }
    }

    private JSONArray favoriteFolders() throws Exception {
        String mid = client.userId();
        if (mid.isEmpty()) mid = client.userInfo().getString("mid");
        JSONObject data = client.get("/x/v3/fav/folder/created/list-all", params("up_mid", mid));
        JSONArray folders = data.optJSONArray("list");
        return folders == null ? new JSONArray() : folders;
    }

    private JSONObject favorites(int p, Map<String, String> filters) throws Exception {
        String fid = filters.get("fid");
        if (fid == null || fid.isEmpty()) {
            JSONArray folders = favoriteFolders();
            if (folders.length() == 0) return page(new JSONArray(), p, false);
            fid = folders.getJSONObject(0).getString("id");
        }
        final String folder = digits(fid);
        return FilteredPages.load(p, hasPlayFilter(filters), upstream -> favoritePage(upstream, folder, filters));
    }

    private JSONObject favoritePage(int p, String fid, Map<String, String> filters) throws Exception {
        JSONObject data = client.get("/x/v3/fav/resource/list", params("media_id", fid, "pn", String.valueOf(p), "ps", "20", "platform", "web", "order", "mtime"));
        JSONArray a = data.optJSONArray("medias"), out = new JSONArray();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject v = a.getJSONObject(i);
            JSONObject counts = v.optJSONObject("cnt_info");
            JSONObject candidate = new JSONObject().put("play", counts == null ? JSONObject.NULL : counts.opt("play"));
            if (isBvid(v.optString("bvid")) && VideoFilters.apply(new JSONArray().put(candidate), filters, false).length() > 0)
                out.put(card("video:" + v.getString("bvid"), v.optString("title"), "收藏视频", v.optString("cover")));
        }
        return page(out, p, data.optBoolean("has_more", false));
    }

    private JSONObject dynamics(int p) throws Exception {
        if (p == 1) dynamicCursors.clear();
        if (p > 1 && !dynamicCursors.containsKey(p)) throw new IllegalStateException("动态使用游标分页，请从第 1 页按顺序翻页");
        JSONObject data = client.get("/x/polymer/web-dynamic/v1/feed/all", params("type", "video", "offset", p == 1 ? "" : dynamicCursors.get(p), "page", String.valueOf(p)));
        JSONArray a = data.optJSONArray("items"), out = new JSONArray();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject item = a.getJSONObject(i);
            if (item.optJSONObject("orig") != null) item = item.getJSONObject("orig");
            JSONObject modules = item.optJSONObject("modules");
            JSONObject dynamic = modules == null ? null : modules.optJSONObject("module_dynamic");
            JSONObject major = dynamic == null ? null : dynamic.optJSONObject("major");
            JSONObject arc = major == null ? null : major.optJSONObject("archive");
            if (arc != null && isBvid(arc.optString("bvid"))) out.put(card("video:" + arc.getString("bvid"), arc.optString("title"), arc.optString("duration_text"), arc.optString("cover")));
            JSONObject pgc = major == null ? null : major.optJSONObject("pgc");
            if (pgc != null && pgc.optLong("season_id") > 0) out.put(card("season:" + pgc.optLong("season_id"), pgc.optString("title"), "追番动态", pgc.optString("cover")));
        }
        String cursor = data.optString("offset");
        boolean more = data.optBoolean("has_more", false) && !cursor.isEmpty();
        if (more) dynamicCursors.put(p + 1, cursor);
        return page(out, p, more);
    }

    private JSONObject history(int p) throws Exception {
        if (p == 1) historyCursors.clear();
        if (p > 1 && !historyCursors.containsKey(p)) throw new IllegalStateException("历史记录使用游标分页，请从第 1 页按顺序翻页");
        Map<String, String> query = params("ps", "20", "type", "archive");
        if (p > 1) {
            JSONObject c = historyCursors.get(p);
            query.put("max", c.optString("max")); query.put("view_at", c.optString("view_at")); query.put("business", c.optString("business"));
        }
        JSONObject data = client.get("/x/web-interface/history/cursor", query);
        JSONArray a = data.optJSONArray("list"), out = new JSONArray();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject v = a.getJSONObject(i), h = v.optJSONObject("history");
            if (h == null) continue;
            if (isBvid(h.optString("bvid"))) out.put(card("video:" + h.getString("bvid"), v.optString("title"), "已观看 " + v.optInt("progress") + " 秒", v.optString("cover")));
            else if (h.optLong("epid") > 0) out.put(card("ep:" + h.optLong("epid"), v.optString("title"), "影视历史", v.optString("cover")));
        }
        JSONObject cursor = data.optJSONObject("cursor");
        boolean more = a != null && a.length() >= PAGE_SIZE && cursor != null && cursor.optLong("max") > 0;
        if (more) historyCursors.put(p + 1, cursor);
        return page(out, p, more);
    }

    private JSONObject tagged(String id, int p, Map<String, String> filters) throws Exception {
        loadCatalog();
        JSONObject selected = null;
        JSONArray all = interests.getJSONArray("categories");
        for (int i = 0; i < all.length(); i++) if (id.equals(all.getJSONObject(i).optString("id"))) selected = all.getJSONObject(i);
        if (selected == null) throw new IllegalArgumentException("未找到兴趣分类，请刷新订阅");
        String keyword = filters.get("keyword");
        if (keyword == null || keyword.isEmpty()) keyword = selected.getJSONArray("queries").getString(0);
        String order = filters.get("order");
        if (order == null || !Arrays.asList("totalrank", "pubdate", "click", "dm", "stow").contains(order)) order = "totalrank";
        String duration = filters.get("duration");
        if (duration == null || !Arrays.asList("0", "1", "2", "3", "4").contains(duration)) duration = "0";
        try {
            final String query = keyword, sort = order, length = duration;
            return FilteredPages.load(p, hasPlayFilter(filters), upstream -> {
                JSONObject data = client.search(query, upstream, sort, length);
                JSONArray source = data.optJSONArray("result");
                if (source == null) throw new IllegalStateException("搜索结果格式异常，请稍后重试");
                JSONArray rows = searchCards(VideoFilters.apply(source, filters, false));
                return page(rows, upstream, upstream < data.optInt("numPages", upstream));
            });
        } catch (Exception e) {
            // Only use a broad category snapshot for unfiltered page 1; never silently substitute a different query.
            if (p != 1 || filters.containsKey("keyword") || filters.containsKey("order") || !"0".equals(duration)
                    || (filters.get("plays") != null && !"all".equals(filters.get("plays")))) throw e;
            JSONArray categories = catalog.optJSONArray("categories");
            for (int i = 0; categories != null && i < categories.length(); i++) {
                JSONObject c = categories.getJSONObject(i);
                if (!id.equals(c.optString("id"))) continue;
                JSONArray out = searchCards(c.optJSONArray("items"));
                if (out.length() > 0) return page(out, 1, false).put("msg", "实时请求失败，显示 Actions 公共缓存：" + c.optString("updated_at"));
            }
            throw e;
        }
    }

    private static boolean hasPlayFilter(Map<String, String> filters) {
        return Arrays.asList("10k_100k", "100k_plus", "1k_10k", "lt_1k").contains(filters.get("plays"));
    }

    private JSONArray searchCards(JSONArray a) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject v = a.getJSONObject(i);
            if (isBvid(v.optString("bvid"))) out.put(card("video:" + v.getString("bvid"), v.optString("title"), ("study".equals(mode) ? durationMinutes(v.optString("duration")) : v.optString("author") + " · " + v.optString("duration")), v.optString("pic")));
        }
        return out;
    }

    private static String durationMinutes(String duration) {
        try {
            String[] parts = (duration == null ? "" : duration.trim()).split(":", -1);
            if (parts.length > 3) return "时长未知";
            long seconds = 0;
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].matches("[0-9]+")) return "时长未知";
                long part = Long.parseLong(parts[i]);
                if ((i > 0 && part >= 60) || seconds > (Long.MAX_VALUE - part) / 60)
                    return "时长未知";
                seconds = seconds * 60 + part;
            }
            if (seconds <= 0) return "时长未知";
            return seconds < 60 ? "不足1分钟" : seconds / 60 + "分钟";
        } catch (NumberFormatException invalid) {
            return "时长未知";
        }
    }

    private JSONObject videoDetail(String bvid) throws Exception {
        JSONObject data = client.get("/x/web-interface/wbi/view", params("bvid", checkedBvid(bvid)));
        JSONObject owner = data.optJSONObject("owner");
        JSONObject vod = card("video:" + bvid, data.optString("title"), "", data.optString("pic"))
            .put("vod_content", VideoMetadata.description(data)).put("vod_year", VideoMetadata.year(data)).put("vod_actor", owner == null ? "" : owner.optString("name"))
            .put("type_name", data.optString("tname"));
        JSONObject collection = BiliCollections.expand(client, data);
        if (collection != null) {
            JSONArray entries = collection.optJSONArray("entries");
            String progress = collection.optBoolean("complete")
                    ? "UP主合集 · " + collection.optInt("archive_count") + " 个视频 · " + collection.optInt("count") + " 个选集"
                    : "合集目录尚未加载完整：已载 " + collection.optInt("archive_count")
                        + (collection.optInt("total") > 0 ? "/" + collection.optInt("total") : "")
                        + " 个视频；重新进入详情可重试。";
            if (entries != null && entries.length() > 0) {
                List<String> tracks = new ArrayList<>();
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.getJSONObject(i);
                    tracks.add(label((i + 1) + ". " + entry.optString("title")) + "$play:"
                            + checkedBvid(entry.getString("bvid")) + ":" + digits(entry.optString("cid", "0")));
                }
                String pic = collection.optString("pic", data.optString("pic"));
                if (pic.startsWith("//")) pic = "https:" + pic;
                if (pic.startsWith("http://")) pic = "https://" + pic.substring(7);
                vod.put("vod_name", clean(collection.optString("name", data.optString("title"))))
                        .put("vod_pic", pic).put("type_name", "UP主合集").put("vod_remarks", progress)
                        .put("vod_content", clean(collection.optString("description")) + "\n" + progress
                                + "\n\n本次命中视频的信息：\n" + VideoMetadata.description(data));
                return qualityChoices(vod.put("vod_play_from", "自动").put("vod_play_url", join(tracks, "#")));
            }
            vod.put("vod_content", vod.optString("vod_content") + "\n\n" + progress);
        }
        List<String> pages = new ArrayList<>();
        JSONArray a = data.optJSONArray("pages");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject v = a.getJSONObject(i);
            pages.add(label((i + 1) + ". " + v.optString("part")) + "$play:" + bvid + ":" + digits(v.optString("cid")));
        }
        if (pages.isEmpty()) pages.add("播放$play:" + bvid + ":" + data.optString("cid", "0"));
        return qualityChoices(vod.put("vod_play_from", "自动").put("vod_play_url", join(pages, "#")));
    }

    private JSONObject seasonDetail(String key, String value) throws Exception {
        JSONObject data = client.get("/pgc/view/web/season", params(key, digits(value)));
        JSONObject vod = card("season:" + data.getString("season_id"), data.optString("title"), "按账号权益播放", data.optString("cover"))
            .put("vod_content", data.optString("evaluate") + "\n会员、付费、地域及 DRM 限制以 Bilibili 为准。");
        List<String> names = new ArrayList<>(), lines = new ArrayList<>();
        List<String> main = pgcEpisodes(data.optJSONArray("episodes"));
        if (!main.isEmpty()) { names.add("Bilibili 正片"); lines.add(join(main, "#")); }
        JSONArray sections = data.optJSONArray("section");
        for (int s = 0; sections != null && s < sections.length(); s++) {
            JSONObject section = sections.getJSONObject(s);
            List<String> tracks = pgcEpisodes(section.optJSONArray("episodes"));
            if (!tracks.isEmpty()) { names.add(label(section.optString("title", "花絮"))); lines.add(join(tracks, "#")); }
        }
        return qualityChoices(vod.put("vod_play_from", join(names, "$$$")).put("vod_play_url", join(lines, "$$$")));
    }

    private List<String> pgcEpisodes(JSONArray a) throws Exception {
        List<String> tracks = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject e = a.getJSONObject(i);
            tracks.add(label(e.optString("title") + " " + e.optString("long_title")) + "$playep:" + e.getString("id"));
        }
        return tracks;
    }

    private boolean hasSession() { return client != null && client.hasSession(); }

    private JSONObject accountNotice() throws Exception {
        String message = "请返回源选择，进入 Bilibili 扫码登录 卡片登录账号";
        return page(new JSONArray().put(card("notice:" + message, "请先在 Bilibili 扫码登录 中登录", "账号在各模块间共享", "")), 1, false);
    }

    private JSONObject accountCards() throws Exception {
        return page(new JSONArray()
            .put(card("auth:login", "扫码登录 / 重新登录", hasSession() ? "本机已保存登录信息" : "未登录", ""))
            .put(card("auth:status", "检查登录状态", "查看当前账号", ""))
            .put(card("auth:logout", "退出登录", "清除本机登录信息", "")), 1, false);
    }

    private JSONObject authDetail(String action) throws Exception {
        if ("logout".equals(action)) {
            cancelPendingLogin(true);
            client.logout(); dynamicCursors.clear(); historyCursors.clear();
            toast("已清除本机 Bilibili 登录信息");
            return card("auth:logout", "已退出登录", "", "").put("vod_content", "登录信息已从设备移除。");
        }
        if ("status".equals(action)) {
            JSONObject user = client.userInfo();
            return card("auth:status", user.optString("uname", "未登录"), user.optBoolean("isLogin") ? "已登录" : "未登录", user.optString("face"))
                .put("vod_content", "账号 UID：" + user.optString("mid") + "\n扫码成功后，请重新加载源配置或重启 TVBox，以刷新收藏夹筛选项。");
        }
        startLogin();
        return card("auth:login", "用 Bilibili 手机客户端扫描登录窗口二维码", qrMessage, qrPicture)
            .put("vod_content", "打开 Bilibili 手机客户端扫一扫，扫描弹窗中的二维码并确认。弹窗实时显示登录状态，可以刷新二维码；没有弹窗时可扫描详情封面。成功后重新加载源配置或重启 TVBox，以刷新收藏夹。\n二维码约 3 分钟有效，过期后重新进入本页生成。\n" + qrMessage)
            .put("vod_play_from", "登录说明").put("vod_play_url", "扫码成功后重载源配置$noop");
    }

    private void startLogin() throws Exception {
        final long generation;
        synchronized (BiliStudy.class) {
            if (qrRunning && System.currentTimeMillis() - qrCreated < 175000) {
                showLoginDialog(LOGIN_GENERATION.get());
                return;
            }
            cancelPendingLogin(false);
            generation = LOGIN_GENERATION.incrementAndGet();
            qrClient = client;
            qrCreated = System.currentTimeMillis();
            qrMessage = "正在生成二维码";
            qrRunning = true;
        }
        try {
            JSONObject qr = client.beginQr();
            final String key = qr.getString("qrcode_key");
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8"); hints.put(EncodeHintType.MARGIN, 3);
            BitMatrix matrix = new QRCodeWriter().encode(qr.getString("url"), BarcodeFormat.QR_CODE, 640, 640, hints);
            int[] square = new int[640 * 640];
            for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++) square[y * 640 + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
            Bitmap image = Bitmap.createBitmap(square, 640, 640, Bitmap.Config.ARGB_8888);
            // The native dialog uses a square bitmap directly. The 3:4 cover remains a fallback.
            Bitmap cover = Bitmap.createBitmap(960, 1280, Bitmap.Config.ARGB_8888);
            int[] portrait = new int[960 * 1280];
            Arrays.fill(portrait, 0xffffffff);
            for (int y = 0; y < 640; y++) System.arraycopy(square, y * 640, portrait, (y + 320) * 960 + 160, 640);
            cover.setPixels(portrait, 0, 960, 0, 0, 960, 1280);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            cover.compress(Bitmap.CompressFormat.PNG, 100, png); cover.recycle();
            synchronized (BiliStudy.class) {
                if (generation != LOGIN_GENERATION.get()) return;
                qrBitmap = image;
                qrPicture = LocalServer.get().put(png.toByteArray(), "image/png", 180000L);
                qrMessage = "等待扫码，请使用 Bilibili 手机客户端扫一扫";
                final long remaining = Math.max(1, 180000 - (System.currentTimeMillis() - qrCreated));
                qrWorker = new Thread(() -> {
                    QrPoller.Result result = QrPoller.await(() -> client.pollQr(key), new QrPoller.Listener() {
                        public boolean active() { return generation == LOGIN_GENERATION.get(); }
                        public void update(String message) {
                            if (!active()) return;
                            qrMessage = message;
                            QR_DIALOG.updateStatus(message);
                        }
                    }, remaining);
                    synchronized (BiliStudy.class) {
                        if (generation != LOGIN_GENERATION.get()) return;
                        qrRunning = false;
                        qrWorker = null;
                        if (result == QrPoller.Result.SUCCESS) {
                            LocalServer.get().remove(qrPicture);
                            qrMessage = "登录成功，请重载源配置或重启 TVBox";
                            QR_DIALOG.updateStatus(qrMessage);
                            toast(qrMessage);
                        }
                    }
                }, "bili-qr-login");
                qrWorker.setDaemon(true);
                qrWorker.start();
            }
            showLoginDialog(generation);
        } catch (Exception failure) {
            synchronized (BiliStudy.class) {
                if (generation == LOGIN_GENERATION.get()) {
                    qrRunning = false;
                    qrMessage = friendly(failure);
                    QR_DIALOG.updateStatus(qrMessage);
                }
            }
            throw failure;
        }
    }

    private void showLoginDialog(long generation) {
        if (generation != LOGIN_GENERATION.get() || qrBitmap == null) return;
        QR_DIALOG.show(qrBitmap, qrMessage, () -> {
            Thread refresh = new Thread(() -> {
                cancelPendingLogin(false);
                try { startLogin(); }
                catch (Exception failure) { toast(friendly(failure)); }
            }, "bili-qr-refresh");
            refresh.setDaemon(true);
            refresh.start();
        }, () -> cancelPendingLogin(false));
    }

    /** Cancels pending authentication without signing the existing account out. */
    private static synchronized void cancelPendingLogin(boolean dismiss) {
        LOGIN_GENERATION.incrementAndGet();
        qrRunning = false;
        Thread previous = qrWorker;
        qrWorker = null;
        if (previous != null) previous.interrupt();
        BiliClient pending = qrClient;
        qrClient = null;
        if (pending != null) pending.cancelQr();
        if (!qrPicture.isEmpty()) LocalServer.get().remove(qrPicture);
        qrPicture = "";
        qrBitmap = null;
        qrCreated = 0;
        if (dismiss) QR_DIALOG.dismiss();
    }

    private synchronized void loadCatalog() throws Exception {
        if (interests != null && System.currentTimeMillis() - loadedAt < 1800000) return;
        JSONObject nextInterests;
        try { nextInterests = new JSONObject(readPublic(interestsUrl)); }
        catch (Exception e) { if (interests != null) return; throw new IllegalStateException("无法加载兴趣分类，请检查订阅地址和 GitHub Pages 网络", e); }
        if (nextInterests.optJSONArray("categories") == null) throw new IllegalStateException("兴趣配置格式错误");
        interests = nextInterests;
        try { catalog = new JSONObject(readPublic(catalogUrl)); }
        catch (Exception ignored) { if (catalog == null) catalog = new JSONObject(); }
        loadedAt = System.currentTimeMillis();
    }

    private static String readPublic(String address) throws Exception {
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null) throw new IllegalArgumentException("公共配置必须使用 HTTPS");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(15000);
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("目录 HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); if (out.size() > 4 * 1024 * 1024) throw new IllegalStateException("目录文件过大"); }
                return out.toString("UTF-8");
            }
        } finally { c.disconnect(); }
    }

    private void toast(final String message) {
        if (context == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() { public void run() { Toast.makeText(context, message, Toast.LENGTH_LONG).show(); } });
    }

    private static JSONObject category(String id, String name) throws Exception { return new JSONObject().put("type_id", id).put("type_name", name); }
    private static JSONObject card(String id, String name, String remarks, String pic) throws Exception {
        if (pic.startsWith("//")) pic = "https:" + pic;
        if (pic.startsWith("http://") && !pic.startsWith("http://127.0.0.1:")) pic = "https://" + pic.substring(7);
        return new JSONObject().put("vod_id", id).put("vod_name", clean(name)).put("vod_remarks", clean(remarks)).put("vod_pic", pic);
    }
    private static JSONObject page(JSONArray list, int p, boolean more) throws Exception {
        return new JSONObject().put("list", list).put("page", p).put("pagecount", more ? p + 1 : p).put("limit", PAGE_SIZE)
            .put("total", more ? (p + 1) * PAGE_SIZE : (p - 1) * PAGE_SIZE + list.length());
    }
    private static JSONObject errorPage(Exception e) throws Exception {
        String text = friendly(e);
        return page(new JSONArray().put(card("notice:" + text, "加载失败，打开查看原因", text, "")), 1, false).put("msg", text);
    }
    private static String friendly(Exception e) {
        String message = e.getMessage();
        if (message == null) return "请求失败，请检查网络或重新登录";
        // API errors contain codes and descriptions only; never render request URLs or session material.
        if (message.contains("SESSDATA") || message.contains("ticket=") || message.contains("qrcode_key=")) return "登录请求失败，请重新生成二维码";
        return message.length() > 240 ? message.substring(0, 240) : message;
    }
    @SuppressWarnings("deprecation")
    private static String clean(String s) {
        String value = s == null ? "" : s;
        // Published catalog titles are already plain text; only API markup needs Android Html.
        if (value.indexOf('<') < 0 && value.indexOf('&') < 0) return value.trim();
        return Html.fromHtml(value).toString().trim();
    }
    private static String label(String s) { return clean(s).replace('$', '＄').replace('#', '＃'); }
    private static boolean isBvid(String s) { return s != null && s.matches("BV[0-9A-Za-z]{10}"); }
    private static String checkedBvid(String s) { if (!isBvid(s)) throw new IllegalArgumentException("BV 编号无效"); return s; }
    private static String digits(String s) { if (s == null || !s.matches("[0-9]{1,20}")) throw new IllegalArgumentException("数字编号无效"); return s; }
    private static String join(List<String> a, String separator) { StringBuilder s = new StringBuilder(); for (String v : a) { if (s.length() > 0) s.append(separator); s.append(v); } return s.toString(); }
    private static Map<String, String> params(String... pairs) { Map<String, String> out = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]); return out; }
}
