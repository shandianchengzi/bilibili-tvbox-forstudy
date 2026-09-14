import com.github.catvod.spider.bili.LocalServer;
import com.github.catvod.spider.bili.PlaybackQuality;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.xml.parsers.DocumentBuilderFactory;

/** Exercises selectable TVBox lines through actual single-URL, loopback MPD playback. */
public final class PlaybackQualityTest {
    private static final String NS = "urn:mpeg:dash:schema:mpd:2011";
    private static int assertions;

    public static void main(String[] arguments) throws Exception {
        try {
            testAvailable();
            testLines();
            testIds();
            testPlayer();
            testRestrictions();
            System.out.println("PlaybackQualityTest: " + assertions + " assertions passed");
        } finally {
            LocalServer.get().clear();
        }
    }

    private static void testAvailable() throws Exception {
        JSONObject data = data();
        data.put("accept_quality", new JSONArray().put(127).put(126).put(120).put(80).put(64));
        data.put("support_formats", new JSONArray().put(new JSONObject().put("quality", 127)));
        check(PlaybackQuality.available(data).equals(Arrays.asList(120, 80, 64)),
                "Only actual playable tracks may appear, descending and without duplicate codecs");
        JSONObject advertisedOnly = new JSONObject().put("accept_quality", new JSONArray().put(120).put(80));
        check(PlaybackQuality.available(advertisedOnly).isEmpty(), "Advertised account-locked grades must not become choices");
        check(PlaybackQuality.available(null).isEmpty(), "Missing response must not advertise qualities");
        check(PlaybackQuality.available(direct(64)).equals(Arrays.asList(64)), "Single durl must advertise its actual quality");
        JSONObject unknownDirect = direct(64);
        unknownDirect.remove("quality");
        check(PlaybackQuality.available(unknownDirect).isEmpty(), "Unidentified durl cannot promise a manual quality");
        JSONObject splitDirect = direct(64);
        splitDirect.getJSONArray("durl").put(new JSONObject().put("url", "https://test.bilivideo.com/part2.mp4"));
        check(PlaybackQuality.available(splitDirect).isEmpty(), "Unsupported multi-segment durl must not be advertised");
        JSONObject missingAudio = data();
        missingAudio.getJSONObject("dash").put("audio", new JSONArray());
        check(PlaybackQuality.available(missingAudio).isEmpty(), "Silent video cannot be advertised as playable");
        JSONObject invalidTrack = data();
        invalidTrack.getJSONObject("dash").getJSONArray("video").put(track(127, "av01.0.12M.08", false)
                .put("baseUrl", "file:///tmp/private"));
        check(PlaybackQuality.available(invalidTrack).equals(Arrays.asList(120, 80, 64)),
                "Malformed media cannot introduce an available quality");
        for (Object flag : new Object[]{true, 1, "1"}) {
            check(PlaybackQuality.available(data().put("is_drm", flag)).isEmpty(), "DRM streams cannot become selectable");
        }
        check("4K".equals(PlaybackQuality.name(120)), "4K label must be recognizable");
        check("1080P".equals(PlaybackQuality.name(80)), "1080P label must be recognizable");
    }

    private static void testLines() throws Exception {
        String[] names = {"Bilibili 视频", "合集 · 芯片设计", "Bilibili 正片"};
        String[] lines = {
                "1. 上集$play:BV1234567890:11#2. 下集$play:BV1234567890:12",
                "1. 工程实践$play:BV2234567890:21#2. 设计复盘$play:BV3234567890:0",
                "第1集$playep:31#第2集$playep:32"
        };
        String originalContent = "BV 号：BV1234567890\n发布时间：2026-09-14\n播放量：1,234 次\n原始简介";
        JSONObject vod = new JSONObject().put("vod_play_from", String.join("$$$", names))
                .put("vod_play_url", String.join("$$$", lines)).put("vod_content", originalContent);
        PlaybackQuality.addChoices(vod, data());
        String[] resultNames = vod.getString("vod_play_from").split("\\$\\$\\$", -1);
        String[] resultLines = vod.getString("vod_play_url").split("\\$\\$\\$", -1);
        check(resultNames.length == 12 && resultLines.length == 12,
                "Each original video, collection, and PGC line needs automatic plus three quality lines");
        int[] qualities = {120, 80, 64};
        for (int section = 0; section < names.length; section++) {
            int start = section * 4;
            check(resultNames[start].equals(names[section] + " · 自动"), "Automatic line must retain its section identity");
            check(resultLines[start].equals(lines[section]), "Automatic playback IDs must remain backward compatible");
            String[] oldEpisodes = lines[section].split("#", -1);
            for (int q = 0; q < qualities.length; q++) {
                check(resultNames[start + q + 1].equals(names[section] + " · " + PlaybackQuality.name(qualities[q])),
                        "Manual line lost its quality label or original section");
                String[] newEpisodes = resultLines[start + q + 1].split("#", -1);
                check(newEpisodes.length == oldEpisodes.length, "Manual selection dropped a part or episode");
                for (int episode = 0; episode < newEpisodes.length; episode++) {
                    check(newEpisodes[episode].equals(oldEpisodes[episode] + "@qn=" + qualities[q]),
                            "Episode label or base playback ID changed when adding a quality");
                    String newId = newEpisodes[episode].substring(newEpisodes[episode].indexOf('$') + 1);
                    String oldId = oldEpisodes[episode].substring(oldEpisodes[episode].indexOf('$') + 1);
                    check(PlaybackQuality.baseId(newId).equals(oldId), "Quality ID failed to round-trip for a BV or PGC episode");
                    check(PlaybackQuality.requested(newId) == qualities[q], "Selected quality was not encoded in every episode");
                }
            }
        }
        check(vod.getString("vod_content").startsWith(originalContent), "Quality instructions must preserve video metadata and description");
        check(vod.getString("vod_content").contains("播放线路"), "Viewer needs a visible instruction for selecting quality");

        JSONObject unchanged = new JSONObject().put("vod_play_from", names[0]).put("vod_play_url", lines[0]);
        PlaybackQuality.addChoices(unchanged, new JSONObject().put("accept_quality", new JSONArray().put(120)));
        check(unchanged.getString("vod_play_from").equals(names[0]) && unchanged.getString("vod_play_url").equals(lines[0]),
                "Failed or unusable quality discovery must preserve original automatic playback");
        JSONObject mismatch = new JSONObject().put("vod_play_from", "视频$$$正片").put("vod_play_url", lines[0]);
        expect(IllegalArgumentException.class, () -> PlaybackQuality.addChoices(mismatch, data()),
                "Mismatched TVBox sections must not silently corrupt episode routing");
    }

    private static void testIds() throws Exception {
        String[] original = {"play:BV1234567890:11", "play:BV1234567890:0", "play:BV1234567890", "playep:31"};
        for (String id : original) {
            check(PlaybackQuality.baseId(id).equals(id), "Old playback IDs must be preserved");
            check(PlaybackQuality.requested(id) == 0, "Old playback IDs must select automatic quality");
            for (int quality : new int[]{16, 64, 80, 120, 127}) {
                String explicit = id + "@qn=" + quality;
                check(PlaybackQuality.baseId(explicit).equals(id), "Manual ID lost its video or episode locator");
                check(PlaybackQuality.requested(explicit) == quality, "Manual ID lost its selected quality");
            }
        }
        String[] invalid = {"", "0", "-80", "+80", "080", "8.0", "0x50", "80 ", " 80", "80\n",
                "10000", "999999999999999999999", "80@qn=64", "80#playep:9", "80$https://example.com", "80?x=1", "80/64"};
        for (String suffix : invalid) {
            String id = "play:BV1234567890:11@qn=" + suffix;
            expect(IllegalArgumentException.class, () -> PlaybackQuality.requested(id), "Malformed quality suffix must fail before fetching media");
            expect(IllegalArgumentException.class, () -> PlaybackQuality.baseId(id), "Base-ID parsing must not conceal a malformed suffix");
        }
    }

    private static void testPlayer() throws Exception {
        JSONObject high = PlaybackQuality.player(data(), 120);
        Document highManifest = fetchManifest(high);
        check("video_120".equals(video(highManifest).getAttribute("id")), "Manual 4K must not silently select 1080P AVC");
        check("hev1.1.6.L153.90".equals(video(highManifest).getAttribute("codecs")), "4K-only HEVC must remain playable");
        check("audio_30280".equals(audio(highManifest).getAttribute("id")), "Manual quality must retain a matching audio adaptation set");
        check(highManifest.getElementsByTagNameNS(NS, "BaseURL").item(0).getTextContent().endsWith("?a=1&b=2"),
                "Signed media URL query must survive MPD serialization");

        Document low = fetchManifest(PlaybackQuality.player(data(), 64));
        check("video_64".equals(video(low).getAttribute("id")), "Manual low quality was ignored");
        check("audio_30280".equals(audio(low).getAttribute("id")), "Low-quality playback lost audio");
        Document fullHd = fetchManifest(PlaybackQuality.player(data(), 80));
        check("video_80".equals(video(fullHd).getAttribute("id")), "Manual 1080P selected a different grade");
        check(video(fullHd).getAttribute("codecs").startsWith("avc1."), "AVC should win only within the requested quality");
        Document automatic = fetchManifest(PlaybackQuality.player(data(), 0));
        check("video_80".equals(video(automatic).getAttribute("id")), "Automatic selection must preserve the original up-to-1080P preference");

        JSONObject onlyLow = data();
        onlyLow.getJSONObject("dash").put("video", new JSONArray().put(track(64, "avc1.64001F", false)));
        check("video_64".equals(video(fetchManifest(PlaybackQuality.player(onlyLow, 0))).getAttribute("id")),
                "Automatic playback must fall back to an available lower grade");
        expect(IllegalStateException.class, () -> PlaybackQuality.player(onlyLow, 80), "Explicit 1080P must not silently use the automatic fallback");
    }

    private static void testRestrictions() throws Exception {
        JSONObject lowDirect = direct(64).put("accept_quality", new JSONArray().put(120).put(80).put(64));
        expect(IllegalStateException.class, () -> PlaybackQuality.player(lowDirect, 120), "durl downgrade must not bypass a manual quality choice");
        JSONObject lowPlayer = PlaybackQuality.player(lowDirect, 64);
        check(lowPlayer.get("url") instanceof String, "Direct media must also preserve legacy TVBox string URLs");
        check(lowPlayer.getString("url").equals("https://test.bilivideo.com/video.mp4"), "Matching direct quality should use its actual media URL");
        check(PlaybackQuality.player(lowDirect, 0).getString("url").equals(lowPlayer.getString("url")),
                "Automatic playback may use the provider's returned direct quality");
        JSONObject unknownQuality = direct(64);
        unknownQuality.remove("quality");
        expect(IllegalStateException.class, () -> PlaybackQuality.player(unknownQuality, 64), "Unknown durl quality cannot satisfy an explicit grade");
        for (Object flag : new Object[]{true, 1, "1"}) {
            JSONObject drm = data().put("is_drm", flag);
            expect(IllegalStateException.class, () -> PlaybackQuality.player(drm, 0), "DRM must be rejected in automatic playback");
            expect(IllegalStateException.class, () -> PlaybackQuality.player(drm, 80), "DRM must be rejected in manual playback");
        }
        JSONObject noAudio = data();
        noAudio.getJSONObject("dash").put("audio", new JSONArray());
        expect(IllegalStateException.class, () -> PlaybackQuality.player(noAudio, 80), "Manual playback must refuse silent DASH");
        expect(JSONException.class, () -> PlaybackQuality.player(noAudio, 0), "Automatic playback must refuse silent DASH");
        expect(IllegalStateException.class, () -> PlaybackQuality.player(new JSONObject(), 0), "No stream must produce an actionable failure");
        for (String media : new String[]{"file:///tmp/video.mp4", "https://test.bilivideo.com.evil.example/video.mp4",
                "https://user:secret@test.bilivideo.com/video.mp4"}) {
            JSONObject invalid = direct(64);
            invalid.getJSONArray("durl").getJSONObject(0).put("url", media);
            check(PlaybackQuality.available(invalid).isEmpty(), "Invalid direct media must not be advertised");
            expect(IllegalArgumentException.class, () -> PlaybackQuality.player(invalid, 0), "Direct URL validation cannot be bypassed by automatic playback");
        }
    }

    private static JSONObject data() throws Exception {
        JSONObject dash = new JSONObject().put("duration", 90.5).put("minBufferTime", 1.5)
                .put("video", new JSONArray().put(track(80, "hev1.1.6.L120.90", false))
                        .put(track(120, "hev1.1.6.L153.90", false)).put(track(64, "avc1.64001F", false))
                        .put(track(80, "avc1.640028", false)))
                .put("audio", new JSONArray().put(track(30280, "mp4a.40.2", true)));
        return new JSONObject().put("quality", 120).put("dash", dash);
    }

    private static JSONObject direct(int quality) throws Exception {
        return new JSONObject().put("quality", quality).put("durl", new JSONArray().put(new JSONObject()
                .put("url", "https://test.bilivideo.com/video.mp4")));
    }

    private static JSONObject track(int quality, String codec, boolean audio) throws Exception {
        int height = quality == 120 ? 2160 : quality == 64 ? 720 : 1080;
        return new JSONObject().put("id", quality).put("codecs", codec).put("bandwidth", audio ? 192000 : 2000000)
                .put("mimeType", audio ? "audio/mp4" : "video/mp4")
                .put("baseUrl", "https://test.bilivideo.com/" + (audio ? "audio" : "video") + quality + ".m4s?a=1&b=2")
                .put("width", audio ? 0 : height * 16 / 9).put("height", audio ? 0 : height).put("frameRate", "30")
                .put("SegmentBase", new JSONObject().put("Initialization", "0-733").put("indexRange", "734-2000"));
    }

    private static Document fetchManifest(JSONObject player) throws Exception {
        check(player.get("url") instanceof String, "Legacy TVBox requires url to remain a single string");
        check(player.getInt("parse") == 0 && player.getInt("jx") == 0, "Local DASH must play directly without a parsing service");
        check("application/dash+xml".equals(player.getString("format")), "Player format must declare DASH");
        check("dash".equals(player.getJSONObject("header").getString("TVBox-Format")), "Legacy TVBox DASH hint is missing");
        URL url = new URL(player.getString("url"));
        check("127.0.0.1".equals(url.getHost()) && url.getPath().endsWith(".mpd"), "MPD must use the local server with detectable extension");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        try {
            check(connection.getResponseCode() == 200, "Generated playback MPD was not actually served");
            check("application/dash+xml".equals(connection.getContentType()), "Local MPD has an incorrect MIME type");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document result = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes.toByteArray()));
            check(result.getElementsByTagNameNS(NS, "AdaptationSet").getLength() == 2, "Manifest must have both audio and video");
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private static Element video(Document manifest) {
        return (Element) manifest.getElementsByTagNameNS(NS, "Representation").item(0);
    }

    private static Element audio(Document manifest) {
        return (Element) manifest.getElementsByTagNameNS(NS, "Representation").item(1);
    }

    private interface Operation {
        void run() throws Exception;
    }

    private static void expect(Class<? extends Exception> expected, Operation operation, String message) throws Exception {
        try {
            operation.run();
        } catch (Exception exception) {
            check(expected.isInstance(exception), message + ": unexpected " + exception.getClass().getName());
            return;
        }
        check(false, message);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
