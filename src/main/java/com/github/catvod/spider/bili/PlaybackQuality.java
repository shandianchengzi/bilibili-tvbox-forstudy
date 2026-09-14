package com.github.catvod.spider.bili;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Quality choices use standard TVBox source lines and keep player URLs as strings. */
public final class PlaybackQuality {
    public static final int MAX_REQUEST = 127;
    private static final String MARKER = "@qn=";

    private PlaybackQuality() { }

    public static String baseId(String id) {
        int marker = id.indexOf(MARKER);
        if (marker < 0) return id;
        requested(id); // Reject malformed suffixes before making API requests.
        return id.substring(0, marker);
    }

    /** Zero is the original automatic, up-to-1080P compatibility choice. */
    public static int requested(String id) {
        int marker = id.indexOf(MARKER);
        if (marker < 0) return 0;
        String value = id.substring(marker + MARKER.length());
        if (!value.matches("[1-9][0-9]{0,3}")) throw new IllegalArgumentException("清晰度编号无效");
        return Integer.parseInt(value);
    }

    public static String name(int quality) {
        switch (quality) {
            case 6: return "240P";
            case 16: return "360P";
            case 32: return "480P";
            case 64: return "720P";
            case 74: return "720P 60帧";
            case 80: return "1080P";
            case 112: return "1080P 高码率";
            case 116: return "1080P 60帧";
            case 120: return "4K";
            case 125: return "HDR 真彩";
            case 126: return "杜比视界";
            case 127: return "8K";
            default: return "清晰度 " + quality;
        }
    }

    public static List<Integer> available(JSONObject data) {
        List<Integer> result = new ArrayList<>();
        if (data == null || drm(data)) return result;
        JSONObject dash = data.optJSONObject("dash");
        if (dash != null) return Dash.availableQualities(dash);
        JSONArray durl = data.optJSONArray("durl");
        int quality = data.optInt("quality", 0);
        if (durl != null && durl.length() == 1 && quality > 0) {
            try { mediaUrl(durl.getJSONObject(0).getString("url")); result.add(quality); }
            catch (Exception ignored) { }
        }
        return result;
    }

    /** Preserve every part/episode and UGC section; only append a quality to each play ID. */
    public static void addChoices(JSONObject vod, JSONObject data) throws Exception {
        List<Integer> qualities = available(data);
        if (qualities.isEmpty()) return;
        String[] names = vod.getString("vod_play_from").split("\\$\\$\\$", -1);
        String[] lines = vod.getString("vod_play_url").split("\\$\\$\\$", -1);
        if (names.length != lines.length) throw new IllegalArgumentException("播放线路数量不匹配");
        List<String> outNames = new ArrayList<>(), outLines = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            outNames.add(names[i] + " · 自动");
            outLines.add(lines[i]);
            for (int quality : qualities) {
                List<String> episodes = new ArrayList<>();
                for (String episode : lines[i].split("#", -1)) episodes.add(episode + MARKER + quality);
                outNames.add(names[i] + " · " + name(quality));
                outLines.add(join(episodes, "#"));
            }
        }
        vod.put("vod_play_from", join(outNames, "$$$"));
        vod.put("vod_play_url", join(outLines, "$$$"));
        vod.put("vod_content", vod.optString("vod_content")
                + "\n\n在播放线路中选择清晰度；自动优先最高 1080P。可选档位根据首个视频获取，其他分P/分集以实际返回为准；缺少所选档位时可切回自动。HDR、杜比及高分辨率需要设备支持。");
    }

    /** Manual choices must match an actual stream, never silently substitute a lower grade. */
    public static JSONObject player(JSONObject data, int requested) throws Exception {
        if (drm(data)) throw new IllegalStateException("该内容受 DRM 保护，请使用 Bilibili 官方客户端观看");
        if (requested > 0 && !available(data).contains(requested))
            throw new IllegalStateException("当前视频或账号无法播放 " + name(requested) + "，请切换其他清晰度或自动线路");
        JSONObject headers = new JSONObject(BiliClient.playbackHeaders());
        JSONObject dash = data.optJSONObject("dash");
        if (dash != null) {
            String mpd = requested > 0 ? Dash.createExact(dash, requested) : Dash.create(dash, 80);
            String local = LocalServer.get().put(mpd.getBytes("UTF-8"), "application/dash+xml");
            headers.put("TVBox-Format", "dash");
            return new JSONObject().put("parse", 0).put("url", local).put("header", headers)
                    .put("playUrl", "").put("format", "application/dash+xml").put("jx", 0).put("type", "dash");
        }
        JSONArray durl = data.optJSONArray("durl");
        if (durl != null && durl.length() == 1)
            return new JSONObject().put("parse", 0).put("url", mediaUrl(durl.getJSONObject(0).getString("url")))
                    .put("header", headers);
        throw new IllegalStateException("没有可播放音视频流；可能需要登录、购买或不在授权地区");
    }

    private static boolean drm(JSONObject data) {
        return data.optBoolean("is_drm", false) || data.optInt("is_drm", 0) == 1;
    }

    private static String mediaUrl(String value) throws Exception {
        URL url = new URL(value);
        String host = url.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!("https".equals(url.getProtocol()) || "http".equals(url.getProtocol())) || url.getUserInfo() != null
                || !(host.endsWith(".bilivideo.com") || host.endsWith(".bilivideo.cn")
                || host.endsWith(".bilivideo.net") || host.endsWith(".akamaized.net") || host.endsWith(".hdslb.com")))
            throw new IllegalArgumentException("非 Bilibili 媒体地址");
        return value;
    }

    private static String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) { if (result.length() > 0) result.append(separator); result.append(value); }
        return result.toString();
    }
}
