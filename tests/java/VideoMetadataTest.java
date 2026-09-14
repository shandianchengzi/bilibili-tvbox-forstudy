package com.github.catvod.spider.bili;

import org.json.JSONObject;
import java.util.TimeZone;

/** Offline detail metadata checks using API-shaped JSON, independent of Android. */
public final class VideoMetadataTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        publicTimeAndOriginalDescription();
        missingPublicationTime();
        playCounts();
        System.out.println("VideoMetadataTest: " + assertions + " assertions passed");
    }

    private static void publicTimeAndOriginalDescription() throws Exception {
        TimeZone before = TimeZone.getDefault();
        try {
            // 2024-01-01 00:00:00 in Beijing, on the previous day in the device zone.
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            JSONObject video = new JSONObject().put("bvid", "BV1xx411c7mD")
                    .put("pubdate", 1704038400L).put("ctime", 1700000000L)
                    .put("desc", "原始介绍 <example>\n第二行")
                    .put("stat", new JSONObject().put("view", 123456L));
            equal("BV 号：BV1xx411c7mD\n发布时间：2024-01-01 00:00:00（北京时间）"
                    + "\n播放量：123,456 次\n\n原始介绍 <example>\n第二行",
                    VideoMetadata.description(video), "exact metadata and unmodified original introduction");
            equal("2024", VideoMetadata.year(video), "publication year follows Beijing time");
            video.put("pubdate", "1704038400");
            check(VideoMetadata.description(video).contains("2024-01-01 00:00:00"), "integer timestamp strings accepted as seconds");
        } finally { TimeZone.setDefault(before); }
    }

    private static void missingPublicationTime() throws Exception {
        JSONObject video = new JSONObject().put("ctime", 1704038400L).put("desc", "保留原文");
        for (Object invalid : new Object[] {JSONObject.NULL, 0, -1, "not a date", 1704038400000L, 1.5}) {
            video.put("pubdate", invalid);
            check(VideoMetadata.description(video).contains("发布时间：暂无发布时间"), "invalid publication time is explicit");
            equal("", VideoMetadata.year(video), "invalid publication time has no fabricated year");
        }
        video.remove("pubdate");
        check(VideoMetadata.description(video).contains("发布时间：暂无发布时间"), "upload time never substitutes for missing publication time");
        check(VideoMetadata.description(video).endsWith("\n\n保留原文"), "description survives missing fields");
        equal("BV 号：暂无\n发布时间：暂无发布时间\n播放量：暂无数据",
                VideoMetadata.description(null), "entirely missing response remains readable");
    }

    private static void playCounts() throws Exception {
        JSONObject video = new JSONObject();
        check(VideoMetadata.description(video).contains("播放量：暂无数据"), "missing stat is unknown");
        JSONObject stat = new JSONObject();
        video.put("stat", stat);
        check(VideoMetadata.description(video).contains("播放量：暂无数据"), "missing count is unknown");
        stat.put("view", 0);
        check(VideoMetadata.description(video).contains("播放量：0 次"), "real zero views remain zero");
        stat.put("view", "9007199254740993");
        check(VideoMetadata.description(video).contains("播放量：9,007,199,254,740,993 次"), "large exact integer strings retain precision");
        for (Object invalid : new Object[] {-1, JSONObject.NULL, "--", "9223372036854775808", 1.5}) {
            stat.put("view", invalid);
            check(VideoMetadata.description(video).contains("播放量：暂无数据"), "invalid count is unknown");
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        check(expected.equals(actual), message + " expected=" + expected + " actual=" + actual);
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
