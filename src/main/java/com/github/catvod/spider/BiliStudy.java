package com.github.catvod.spider;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.widget.Toast;

import com.github.catvod.crawler.Spider;
import com.github.catvod.spider.bili.BiliClient;
import com.github.catvod.spider.bili.Dash;
import com.github.catvod.spider.bili.LocalServer;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Original CatVod spider. All account/API traffic originates on the user's device. */
public class BiliStudy extends Spider {
    private static final String DEFAULT_BASE = "https://shandianchengzi.github.io/bilibili-tvbox-forstudy/";
    private static final int PAGE_SIZE = 20;
    private static final AtomicLong LOGIN_GENERATION = new AtomicLong();
    private static volatile String qrPicture = "";
    private static volatile String qrMessage = "";
    private static volatile long qrCreated;
    private static volatile boolean qrRunning;
    private Context context;
    private BiliClient client;
    private String mode = "media";
    private String catalogUrl = DEFAULT_BASE + "catalog.json";
    private String interestsUrl = DEFAULT_BASE + "interests.json";
    private JSONObject interests;
    private JSONObject catalog;
    private long loadedAt;
    private final Map<Integer, String> dynamicCursors = new ConcurrentHashMap<>();
    private final Map<Integer, JSONObject> historyCursors = new ConcurrentHashMap<>();

    public void init(Context context, String extend) throws Exception {
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
        classes.put(category("account", "账号 / 扫码登录"));
        if ("media".equals(mode)) {
            classes.put(category("dynamic", "动态"));
            classes.put(category("favorites", "收藏夹"));
            classes.put(category("history", "历史记录"));
            String[] ids = {"2", "7", "3", "4", "5", "1"};
            String[] names = {"电影", "综艺", "纪录片", "国创", "剧集", "番剧"};
            for (int i = 0; i < ids.length; i++) classes.put(category("pgc:" + ids[i], names[i]));
            if (client.hasSession()) {
                try {
                    JSONArray folders = favoriteFolders();
                    JSONArray values = new JSONArray();
                    for (int i = 0; i < folders.length(); i++) {
                        JSONObject f = folders.getJSONObject(i);
                        values.put(new JSONObject().put("n", f.optString("title")).put("v", f.optString("id")));
                    }
                    if (values.length() > 0) filters.put("favorites", new JSONArray().put(
                        new JSONObject().put("key", "fid").put("name", "收藏夹").put("value", values)));
                } catch (Exception ignored) { /* Personal-category requests show the actual error. */ }
            }
        } else {
            loadCatalog();
            JSONArray categories = interests.optJSONArray("categories");
            for (int i = 0; categories != null && i < categories.length(); i++) {
                JSONObject c = categories.getJSONObject(i);
                String id = "tag:" + c.getString("id");
                classes.put(category(id, c.getString("name")));
                JSONArray values = new JSONArray();
                JSONArray queries = c.getJSONArray("queries");
                for (int q = 0; q < queries.length(); q++) values.put(new JSONObject().put("n", queries.getString(q)).put("v", queries.getString(q)));
                filters.put(id, new JSONArray()
                    .put(new JSONObject().put("key", "keyword").put("name", "主题").put("value", values))
                    .put(new JSONObject().put("key", "order").put("name", "排序").put("value", new JSONArray()
                        .put(new JSONObject().put("n", "综合排序").put("v", "totalrank"))
                        .put(new JSONObject().put("n", "最多点击").put("v", "click"))
                        .put(new JSONObject().put("n", "最新发布").put("v", "pubdate"))
                        .put(new JSONObject().put("n", "最多弹幕").put("v", "dm"))
                        .put(new JSONObject().put("n", "最多收藏").put("v", "stow"))))
                    .put(new JSONObject().put("key", "duration").put("name", "时长").put("value", new JSONArray()
                        .put(new JSONObject().put("n", "全部").put("v", "0"))
                        .put(new JSONObject().put("n", "60分钟以上").put("v", "4"))
                        .put(new JSONObject().put("n", "30-60分钟").put("v", "3"))
                        .put(new JSONObject().put("n", "10-30分钟").put("v", "2"))
                        .put(new JSONObject().put("n", "10分钟以下").put("v", "1")))));
            }
        }
        return new JSONObject().put("class", classes).put("filters", filters).toString();
    }

    public String homeVideoContent() throws Exception {
        return page(new JSONArray().put(card("auth:login", "扫码登录 Bilibili", "扫码后在手机确认；Cookie 仅存本机", "")), 1, false).toString();
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        try {
            int p = Math.max(1, Integer.parseInt(pg));
            Map<String, String> f = extend == null ? new HashMap<String, String>() : extend;
            if ("account".equals(tid)) return accountCards().toString();
            if (Arrays.asList("dynamic", "favorites", "history").contains(tid) && !client.hasSession())
                return page(new JSONArray().put(card("auth:login", "请先扫码登录", "进入详情查看二维码", "")), 1, false).toString();
            if (tid.startsWith("pgc:")) return pgc(tid.substring(4), p).toString();
            if ("dynamic".equals(tid)) return dynamics(p).toString();
            if ("favorites".equals(tid)) return favorites(p, f.get("fid")).toString();
            if ("history".equals(tid)) return history(p).toString();
            if (tid.startsWith("tag:")) return tagged(tid.substring(4), p, f).toString();
            return page(new JSONArray(), p, false).toString();
        } catch (Exception e) { return errorPage(e).toString(); }
    }

    public String searchContent(String key, boolean quick) throws Exception { return searchContent(key, quick, "1"); }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        try {
            int p = Math.max(1, Integer.parseInt(pg));
            Matcher bv = Pattern.compile("BV[0-9A-Za-z]{10}").matcher(key);
            Matcher ep = Pattern.compile("(?:^|/)(ep|ss)([0-9]+)").matcher(key.trim());
            if (bv.find()) return page(new JSONArray().put(card("video:" + bv.group(), bv.group(), "打开视频", "")), 1, false).toString();
            if (ep.find()) return page(new JSONArray().put(card((ep.group(1).equals("ep") ? "ep:" : "season:") + ep.group(2), ep.group(), "打开影视", "")), 1, false).toString();
            JSONObject data = client.search(key.trim(), p, "totalrank");
            JSONArray result = searchCards(data.optJSONArray("result"));
            // Search both authored videos and licensed shows; one failed secondary endpoint does not discard videos.
            try {
                for (String kind : Arrays.asList("media_ft", "media_bangumi")) {
                JSONObject shows = client.get("/x/web-interface/wbi/search/type", params("search_type", kind, "keyword", key, "page", pg));
                JSONArray a = shows.optJSONArray("result");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject s = a.getJSONObject(i);
                    if (s.optLong("season_id") > 0) result.put(card("season:" + s.optLong("season_id"), s.optString("title"), "Bilibili 正版影视", s.optString("cover")));
                }
                }
            } catch (Exception ignored) { }
            return page(result, p, p < data.optInt("numPages", p)).toString();
        } catch (Exception e) { return errorPage(e).toString(); }
    }

    public String detailContent(List<String> ids) throws Exception {
        try {
            String id = ids.get(0);
            JSONObject vod;
            if (id.startsWith("auth:")) vod = authDetail(id.substring(5));
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
            if (id.startsWith("noop")) throw new IllegalStateException("扫码成功后返回首页，重新进入动态、收藏夹或历史记录");
            JSONObject data;
            if (id.startsWith("playep:")) {
                data = client.get("/pgc/player/web/v2/playurl", params("ep_id", digits(id.substring(7)), "qn", "80", "fnval", "4048", "fnver", "0", "fourk", "0"));
                if (data.has("video_info")) data = data.getJSONObject("video_info");
            } else {
                String[] parts = id.split(":");
                if (parts.length < 2 || !parts[0].equals("play")) throw new IllegalArgumentException("播放编号无效");
                String bvid = checkedBvid(parts[1]);
                String cid = parts.length > 2 && !parts[2].equals("0") ? digits(parts[2]) : client.get("/x/web-interface/wbi/view", params("bvid", bvid)).getString("cid");
                data = client.get("/x/player/wbi/playurl", params("bvid", bvid, "cid", cid, "qn", "80", "fnval", "4048", "fnver", "0", "fourk", "0"));
            }
            if (data.optBoolean("is_drm", false) || data.optInt("is_drm", 0) == 1)
                throw new IllegalStateException("该内容受 DRM 保护，请使用 Bilibili 官方客户端观看");
            JSONObject headers = new JSONObject(BiliClient.playbackHeaders());
            JSONArray durl = data.optJSONArray("durl");
            if (durl != null && durl.length() == 1) {
                return new JSONObject().put("parse", 0).put("url", mediaUrl(durl.getJSONObject(0).getString("url")))
                    .put("header", headers).toString();
            }
            JSONObject dash = data.optJSONObject("dash");
            if (dash == null) throw new IllegalStateException("没有可播放音视频流；可能需要登录、购买或不在授权地区");
            String mpd = Dash.create(dash, 80);
            String local = LocalServer.get().put(mpd.getBytes("UTF-8"), "application/dash+xml");
            headers.put("TVBox-Format", "dash");
            return new JSONObject().put("parse", 0).put("url", local).put("header", headers)
                .put("playUrl", "").put("format", "application/dash+xml").put("jx", 0).put("type", "dash").toString();
        } catch (Exception e) {
            toast(friendly(e));
            return new JSONObject().put("parse", 0).put("url", "").put("msg", friendly(e)).toString();
        }
    }

    public boolean isVideoFormat(String url) { return url != null && (url.contains(".mpd") || url.contains(".m4s") || url.contains(".mp4")); }
    public boolean manualVideoCheck() { return false; }

    private JSONObject pgc(String type, int p) throws Exception {
        JSONObject data = client.get("/pgc/season/index/result", params("season_type", type, "type", "1", "page", String.valueOf(p), "pagesize", "20", "order", "3", "sort", "0"));
        JSONArray a = data.optJSONArray("list"), out = new JSONArray();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject s = a.getJSONObject(i);
            out.put(card("season:" + s.getString("season_id"), s.optString("title"), s.optString("index_show"), s.optString("cover")));
        }
        return page(out, p, data.optInt("has_next", 0) == 1 || data.optBoolean("has_next", false));
    }

    private JSONArray favoriteFolders() throws Exception {
        String mid = client.userId();
        if (mid.isEmpty()) mid = client.userInfo().getString("mid");
        JSONObject data = client.get("/x/v3/fav/folder/created/list-all", params("up_mid", mid));
        JSONArray folders = data.optJSONArray("list");
        return folders == null ? new JSONArray() : folders;
    }

    private JSONObject favorites(int p, String fid) throws Exception {
        if (fid == null || fid.isEmpty()) {
            JSONArray folders = favoriteFolders();
            if (folders.length() == 0) return page(new JSONArray(), p, false);
            fid = folders.getJSONObject(0).getString("id");
        }
        JSONObject data = client.get("/x/v3/fav/resource/list", params("media_id", digits(fid), "pn", String.valueOf(p), "ps", "20", "platform", "web", "order", "mtime"));
        JSONArray a = data.optJSONArray("medias"), out = new JSONArray();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject v = a.getJSONObject(i);
            if (isBvid(v.optString("bvid"))) out.put(card("video:" + v.getString("bvid"), v.optString("title"), "收藏视频", v.optString("cover")));
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
            JSONObject data = client.search(keyword, p, order, duration);
            return page(searchCards(data.optJSONArray("result")), p, p < data.optInt("numPages", p));
        } catch (Exception e) {
            // Only use a broad category snapshot for unfiltered page 1; never silently substitute a different query.
            if (p != 1 || filters.containsKey("keyword") || filters.containsKey("order") || !"0".equals(duration)) throw e;
            JSONArray categories = catalog.optJSONArray("categories");
            for (int i = 0; categories != null && i < categories.length(); i++) {
                JSONObject c = categories.getJSONObject(i);
                if (!id.equals(c.optString("id"))) continue;
                JSONArray a = c.optJSONArray("items"), out = new JSONArray();
                for (int j = 0; a != null && j < a.length(); j++) {
                    JSONObject v = a.getJSONObject(j);
                    if (isBvid(v.optString("bvid"))) out.put(card("video:" + v.getString("bvid"), v.optString("title"), "缓存目录 · " + v.optString("author"), v.optString("pic")));
                }
                if (out.length() > 0) return page(out, 1, false).put("msg", "实时请求失败，显示 Actions 公共缓存：" + c.optString("updated_at"));
            }
            throw e;
        }
    }

    private JSONArray searchCards(JSONArray a) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject v = a.getJSONObject(i);
            if (isBvid(v.optString("bvid"))) out.put(card("video:" + v.getString("bvid"), v.optString("title"), v.optString("author") + " · " + v.optString("duration"), v.optString("pic")));
        }
        return out;
    }

    private JSONObject videoDetail(String bvid) throws Exception {
        JSONObject data = client.get("/x/web-interface/wbi/view", params("bvid", checkedBvid(bvid)));
        JSONObject owner = data.optJSONObject("owner");
        JSONObject vod = card("video:" + bvid, data.optString("title"), "", data.optString("pic"))
            .put("vod_content", data.optString("desc")).put("vod_actor", owner == null ? "" : owner.optString("name"))
            .put("type_name", data.optString("tname"));
        List<String> lines = new ArrayList<>(), names = new ArrayList<>();
        List<String> pages = new ArrayList<>();
        JSONArray a = data.optJSONArray("pages");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject v = a.getJSONObject(i);
            pages.add(label((i + 1) + ". " + v.optString("part")) + "$play:" + bvid + ":" + v.getString("cid"));
        }
        if (pages.isEmpty()) pages.add("播放$play:" + bvid + ":" + data.optString("cid", "0"));
        names.add("Bilibili 视频"); lines.add(join(pages, "#"));
        JSONObject collection = data.optJSONObject("ugc_season");
        if (collection != null) {
            JSONArray sections = collection.optJSONArray("sections");
            for (int s = 0; sections != null && s < sections.length(); s++) {
                JSONObject section = sections.getJSONObject(s);
                JSONArray episodes = section.optJSONArray("episodes");
                List<String> tracks = new ArrayList<>();
                for (int j = 0; episodes != null && j < episodes.length(); j++) {
                    JSONObject episode = episodes.getJSONObject(j), arc = episode.optJSONObject("arc");
                    String bv = episode.optString("bvid", arc == null ? "" : arc.optString("bvid"));
                    if (isBvid(bv)) tracks.add(label((j + 1) + ". " + episode.optString("title")) + "$play:" + bv + ":" + episode.optString("cid", "0"));
                }
                if (!tracks.isEmpty()) { names.add(label("合集 · " + section.optString("title", collection.optString("title")))); lines.add(join(tracks, "#")); }
            }
        }
        return vod.put("vod_play_from", join(names, "$$$")).put("vod_play_url", join(lines, "$$$"));
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
        return vod.put("vod_play_from", join(names, "$$$")).put("vod_play_url", join(lines, "$$$"));
    }

    private List<String> pgcEpisodes(JSONArray a) throws Exception {
        List<String> tracks = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject e = a.getJSONObject(i);
            tracks.add(label(e.optString("title") + " " + e.optString("long_title")) + "$playep:" + e.getString("id"));
        }
        return tracks;
    }

    private JSONObject accountCards() throws Exception {
        return page(new JSONArray()
            .put(card("auth:login", "扫码登录 / 重新登录", client.hasSession() ? "本机已保存登录信息" : "未登录", ""))
            .put(card("auth:status", "检查登录状态", "查看当前账号", ""))
            .put(card("auth:logout", "退出登录", "清除本机登录信息", "")), 1, false);
    }

    private JSONObject authDetail(String action) throws Exception {
        if ("logout".equals(action)) {
            LOGIN_GENERATION.incrementAndGet(); qrRunning = false;
            if (!qrPicture.isEmpty()) LocalServer.get().remove(qrPicture);
            qrPicture = ""; qrCreated = 0;
            client.logout(); dynamicCursors.clear(); historyCursors.clear();
            toast("已清除本机 Bilibili 登录信息");
            return card("auth:logout", "已退出登录", "", "").put("vod_content", "登录信息已从设备移除。");
        }
        if ("status".equals(action)) {
            JSONObject user = client.userInfo();
            return card("auth:status", user.optString("uname", "未登录"), user.optBoolean("isLogin") ? "已登录" : "未登录", user.optString("face"))
                .put("vod_content", "账号 UID：" + user.optString("mid") + "\n扫码成功后，请重新进入首页以刷新收藏夹筛选项。");
        }
        startLogin();
        return card("auth:login", "用 Bilibili 手机客户端扫描封面二维码", qrMessage, qrPicture)
            .put("vod_content", "打开 Bilibili 手机客户端扫一扫，确认登录。本页会在后台等待确认，成功时电视会提示；随后返回并刷新首页。\n二维码约 3 分钟有效，过期后重新进入本页生成。\n" + qrMessage)
            .put("vod_play_from", "登录说明").put("vod_play_url", "扫码成功后返回首页$noop");
    }

    private synchronized void startLogin() throws Exception {
        synchronized (BiliStudy.class) {
            if (qrRunning && System.currentTimeMillis() - qrCreated < 175000) return;
            JSONObject qr = client.beginQr();
            final String key = qr.getString("qrcode_key");
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8"); hints.put(EncodeHintType.MARGIN, 3);
            BitMatrix matrix = new QRCodeWriter().encode(qr.getString("url"), BarcodeFormat.QR_CODE, 640, 640, hints);
            // TVBox commonly center-crops covers to 3:4. Keep the whole QR inside a portrait canvas.
            Bitmap bitmap = Bitmap.createBitmap(960, 1280, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[960 * 1280];
            Arrays.fill(pixels, 0xffffffff);
            for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++) pixels[(y + 320) * 960 + x + 160] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
            bitmap.setPixels(pixels, 0, 960, 0, 0, 960, 1280);
            ByteArrayOutputStream png = new ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, png); bitmap.recycle();
            if (!qrPicture.isEmpty()) LocalServer.get().remove(qrPicture);
            qrPicture = LocalServer.get().put(png.toByteArray(), "image/png", 180000L);
            qrCreated = System.currentTimeMillis(); qrMessage = "等待扫码"; qrRunning = true;
            final long generation = LOGIN_GENERATION.incrementAndGet();
            Thread poll = new Thread(new Runnable() {
                public void run() {
                    try {
                        for (int attempts = 0; attempts < 60 && LOGIN_GENERATION.get() == generation; attempts++) {
                            Thread.sleep(3000);
                            synchronized (BiliStudy.class) {
                                if (LOGIN_GENERATION.get() != generation) break;
                                int status = client.pollQr(key);
                                if (status == 0) { LocalServer.get().remove(qrPicture); qrMessage = "登录成功，请返回刷新首页"; toast(qrMessage); break; }
                                if (status == 86038) { qrMessage = "二维码已过期，重新进入本页"; break; }
                                qrMessage = status == 86090 ? "已扫码，请在手机确认" : "等待扫码";
                            }
                        }
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    catch (Exception e) { qrMessage = friendly(e); toast(qrMessage); }
                    finally { if (LOGIN_GENERATION.get() == generation) qrRunning = false; }
                }
            }, "bili-qr-login");
            poll.setDaemon(true); poll.start();
        }
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
    private static String clean(String s) { return Html.fromHtml(s == null ? "" : s).toString().trim(); }
    private static String label(String s) { return clean(s).replace('$', '＄').replace('#', '＃'); }
    private static boolean isBvid(String s) { return s != null && s.matches("BV[0-9A-Za-z]{10}"); }
    private static String checkedBvid(String s) { if (!isBvid(s)) throw new IllegalArgumentException("BV 编号无效"); return s; }
    private static String digits(String s) { if (s == null || !s.matches("[0-9]{1,20}")) throw new IllegalArgumentException("数字编号无效"); return s; }
    private static String mediaUrl(String s) throws Exception {
        URL u = new URL(s);
        String host = u.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!("https".equals(u.getProtocol()) || "http".equals(u.getProtocol())) || u.getUserInfo() != null
            || !(host.endsWith(".bilivideo.com") || host.endsWith(".bilivideo.cn") || host.endsWith(".bilivideo.net") || host.endsWith(".akamaized.net") || host.endsWith(".hdslb.com")))
            throw new IllegalArgumentException("非 Bilibili 媒体地址");
        return s;
    }
    private static String join(List<String> a, String separator) { StringBuilder s = new StringBuilder(); for (String v : a) { if (s.length() > 0) s.append(separator); s.append(v); } return s.toString(); }
    private static Map<String, String> params(String... pairs) { Map<String, String> out = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]); return out; }
}
