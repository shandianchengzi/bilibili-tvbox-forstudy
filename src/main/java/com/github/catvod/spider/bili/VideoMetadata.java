package com.github.catvod.spider.bili;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Human-readable details from Bilibili's video-view response. */
public final class VideoMetadata {
    // Last Unix second that is still in the four-digit year 9999 in Shanghai.
    // This also rejects accidentally supplied millisecond timestamps.
    private static final long LAST_PUBLISH_SECOND = 253402271999L;

    private VideoMetadata() {}

    public static String description(JSONObject data) {
        String bvid = data == null ? "" : data.optString("bvid", "").trim();
        String original = data == null ? "" : data.optString("desc", "");
        StringBuilder result = new StringBuilder("BV 号：")
                .append(bvid.isEmpty() ? "暂无" : bvid)
                .append('\n').append("发布时间：");
        long published = publicationSeconds(data);
        if (published > 0) result.append(formatTime(published, "yyyy-MM-dd HH:mm:ss"))
                .append("（北京时间）");
        else result.append("暂无发布时间");
        result.append('\n').append("播放量：").append(playCount(data));
        if (!original.isEmpty()) result.append("\n\n").append(original);
        return result.toString();
    }

    public static String year(JSONObject data) {
        long published = publicationSeconds(data);
        return published > 0 ? formatTime(published, "yyyy") : "";
    }

    private static long publicationSeconds(JSONObject data) {
        // ctime is upload time, so it must not stand in for public release time.
        long seconds = integer(data == null ? null : data.opt("pubdate"));
        return seconds > 0 && seconds <= LAST_PUBLISH_SECOND ? seconds : -1;
    }

    private static String playCount(JSONObject data) {
        JSONObject stat = data == null ? null : data.optJSONObject("stat");
        long views = integer(stat == null ? null : stat.opt("view"));
        return views >= 0 ? NumberFormat.getIntegerInstance(Locale.US).format(views) + " 次" : "暂无数据";
    }

    private static long integer(Object value) {
        if (value == null || value == JSONObject.NULL) return -1;
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private static String formatTime(long seconds, String pattern) {
        SimpleDateFormat formatter = new SimpleDateFormat(pattern, Locale.ROOT);
        formatter.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return formatter.format(new Date(seconds * 1000L));
    }
}
