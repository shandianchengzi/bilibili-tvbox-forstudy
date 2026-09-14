import com.github.catvod.spider.bili.Dash;
import com.github.catvod.spider.bili.LocalServer;
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

/** JVM integration checks; requires a real org.json jar, never Android's throwing stubs. */
public final class DashLocalServerTest {
    private static final String NS = "urn:mpeg:dash:schema:mpd:2011";

    public static void main(String[] arguments) throws Exception {
        testManifest();
        testQualitySelection();
        testQualityValidation();
        testServer();
        System.out.println("DashLocalServerTest: passed");
    }

    private static void testManifest() throws Exception {
        JSONObject payload = new JSONObject().put("duration", 120.25).put("minBufferTime", 1.5);
        JSONObject avc64 = track(64, "avc1.64001F", 1000000, false);
        JSONObject avc80 = track(80, "avc1.640028", 2000000, false);
        JSONObject hevc120 = track(120, "hev1.1.6.L153.90", 4000000, false);
        payload.put("video", new JSONArray().put(hevc120).put(avc64).put(avc80));
        payload.put("audio", new JSONArray().put(track(30216, "mp4a.40.2", 64000, true))
                .put(track(30280, "mp4a.40.2", 192000, true))
                .put(track(30251, "fLaC", 1000000, true)));
        Document document = parse(Dash.create(payload, 120));
        check("PT120.25S".equals(document.getDocumentElement().getAttribute("mediaPresentationDuration")), "Fractional duration lost");
        check(document.getElementsByTagNameNS(NS, "AdaptationSet").getLength() == 2, "Both audio and video required");
        Element video = (Element) document.getElementsByTagNameNS(NS, "Representation").item(0);
        Element audio = (Element) document.getElementsByTagNameNS(NS, "Representation").item(1);
        check("video_120".equals(video.getAttribute("id")), "4K HEVC must not be replaced by lower-resolution AVC");
        check("audio_30280".equals(audio.getAttribute("id")), "AAC preference/bitrate failed");
        check("hev1.1.6.L153.90".equals(video.getAttribute("codecs")), "Video codecs missing");
        check("0-733".equals(((Element) document.getElementsByTagNameNS(NS, "Initialization").item(0)).getAttribute("range")), "Initialization byte range missing");
        check("734-2000".equals(((Element) document.getElementsByTagNameNS(NS, "SegmentBase").item(0)).getAttribute("indexRange")), "Index byte range missing");
        check(document.getElementsByTagNameNS(NS, "BaseURL").item(0).getTextContent().endsWith("?a=1&b=2"), "Signed CDN query was corrupted by XML escaping");
        check(Dash.create(payload, 64).contains("id=\"video_64\""), "Requested lower quality ignored");

        JSONObject snake = track(80, "avc1.640028", 2000000, false);
        snake.put("base_url", snake.remove("baseUrl"));
        JSONObject segment = (JSONObject) snake.remove("SegmentBase");
        snake.put("segment_base", new JSONObject().put("initialization", segment.getString("Initialization"))
                .put("index_range", segment.getString("indexRange")));
        payload.put("video", new JSONArray().put(snake));
        parse(Dash.create(payload, 80));
        payload.put("audio", new JSONArray());
        boolean rejected = false;
        try { Dash.create(payload); } catch (JSONException expected) { rejected = true; }
        check(rejected, "Missing audio must produce an actionable error, not silent playback");
        check(Dash.availableQualities(payload).isEmpty(), "Missing audio must not advertise playable qualities");
    }

    private static void testQualitySelection() throws Exception {
        JSONObject payload = new JSONObject().put("duration", 30).put("audio", new JSONArray()
                .put(track(30280, "mp4a.40.2", 192000, true)));
        payload.put("video", new JSONArray()
                .put(track(80, "hev1.1.6.L153.90", 5000000, false))
                .put(track(80, "avc1.640028", 1000000, false))
                .put(track(120, "av01.0.12M.08", 9000000, false))
                .put(track(64, "avc1.64001F", 700000, false))
                .put(track(120, "hev1.1.6.L153.90", 4000000, false))
                .put(track(80, "avc1.640028", 2000000, false)));
        check(Dash.availableQualities(payload).equals(Arrays.asList(120, 80, 64)),
                "Quality menu must contain distinct, descending, actually playable qualities");
        Element video = video(Dash.createExact(payload, 80));
        check("avc1.640028".equals(video.getAttribute("codecs")), "AVC must win within the selected quality");
        check("2000000".equals(video.getAttribute("bandwidth")), "Same-quality AVC must use its highest bitrate");
        video = video(Dash.createExact(payload, 120));
        check("video_120".equals(video.getAttribute("id")), "Exact 4K selection was downgraded");
        check("hev1.1.6.L153.90".equals(video.getAttribute("codecs")), "HEVC should precede AV1 for compatibility");
        check("video_64".equals(video(Dash.createExact(payload, 64)).getAttribute("id")),
                "Explicit low-quality selection was ignored");
        check("video_80".equals(video(Dash.create(payload, 112)).getAttribute("id")),
                "Fallback must use highest quality below request");
        check("video_64".equals(video(Dash.create(payload, 32)).getAttribute("id")),
                "Fallback below every available quality must use the lowest available quality");
        check("video_120".equals(video(Dash.create(payload, 0)).getAttribute("id")),
                "Unbounded quality selection must use highest available resolution");
        check("video_80".equals(video(Dash.create(payload)).getAttribute("id")),
                "Default playback should preserve 1080P preference");
        expectExactFailure(payload, 112, "Unavailable exact quality must not silently downgrade");
        expectExactFailure(payload, 0, "Zero is not a valid exact quality");
        for (int quality : Dash.availableQualities(payload)) {
            check(("video_" + quality).equals(video(Dash.createExact(payload, quality)).getAttribute("id")),
                    "Advertised quality cannot generate its promised manifest");
        }
    }

    private static void testQualityValidation() throws Exception {
        JSONObject payload = new JSONObject().put("duration", 30).put("audio", new JSONArray()
                .put(track(30280, "mp4a.40.2", 192000, true)));
        JSONObject invalidRange = track(126, "avc1.640028", 1000000, false);
        invalidRange.getJSONObject("SegmentBase").put("Initialization", "733-0");
        JSONArray tracks = new JSONArray()
                .put(track(80, "avc1.640028", 2000000, false))
                .put(track(120, "hev1.1.6.L153.90", 4000000, false).put("baseUrl", "file:///tmp/video"))
                .put(track(121, "avc1.640028", 1000000, false).put("baseUrl", "https://user:pass@test.bilivideo.com/video"))
                .put(track(122, "avc1.640028", 1000000, false).put("mimeType", "video/webm"))
                .put(track(123, "", 1000000, false))
                .put(track(124, "avc1.640028", 0, false))
                .put(track(125, "avc1.\u0001", 1000000, false))
                .put(invalidRange)
                .put(track(0, "avc1.640028", 1000000, false))
                .put(JSONObject.NULL)
                .put(track(80, "avc1.640028", 9000000, false).put("baseUrl", "invalid"));
        payload.put("video", tracks);
        check(Dash.availableQualities(payload).equals(Arrays.asList(80)),
                "Invalid URL, MIME, byte ranges, XML, bitrate, or quality must not appear in the menu");
        check("2000000".equals(video(Dash.createExact(payload, 80)).getAttribute("bandwidth")),
                "Invalid high-bitrate track must not hide a usable track with the same quality");
        for (int quality = 120; quality <= 126; quality++)
            expectExactFailure(payload, quality, "Exact selection accepted an invalid media track");

        JSONObject invalidAudio = track(30280, "mp4a.40.2", 192000, true).put("mimeType", "video/mp4");
        payload.put("audio", new JSONArray().put(invalidAudio));
        check(Dash.availableQualities(payload).isEmpty(), "Invalid audio must suppress every advertised quality");
        expectExactFailure(payload, 80, "Exact playback must reject invalid audio");
        invalidAudio.put("mimeType", "audio/mp4").put("baseUrl", "file:///tmp/audio");
        check(Dash.availableQualities(payload).isEmpty(), "Invalid audio URI must suppress every advertised quality");
        payload.put("audio", new JSONArray().put(invalidAudio).put(track(30216, "mp4a.40.2", 64000, true)));
        Document document = parse(Dash.createExact(payload, 80));
        Element audio = (Element) document.getElementsByTagNameNS(NS, "Representation").item(1);
        check("audio_30216".equals(audio.getAttribute("id")), "Valid audio fallback was lost to invalid high-bitrate audio");

        payload.put("duration", 0);
        check(Dash.availableQualities(payload).isEmpty(), "Invalid duration must suppress every advertised quality");
        expectExactFailure(payload, 80, "Exact playback must reject invalid duration");
        check(Dash.availableQualities(null).isEmpty(), "Absent DASH data must have no selectable quality");
        expectExactFailure(null, 80, "Absent DASH data must not produce a manifest");
    }

    private static Element video(String manifest) throws Exception {
        return (Element) parse(manifest).getElementsByTagNameNS(NS, "Representation").item(0);
    }

    private static void expectExactFailure(JSONObject payload, int quality, String message) throws Exception {
        boolean rejected = false;
        try { Dash.createExact(payload, quality); } catch (JSONException expected) { rejected = true; }
        check(rejected, message);
    }

    private static JSONObject track(int quality, String codec, int bandwidth, boolean audio) throws Exception {
        return new JSONObject().put("id", quality).put("codecs", codec).put("bandwidth", bandwidth)
                .put("mimeType", audio ? "audio/mp4" : "video/mp4")
                .put("baseUrl", "https://test.bilivideo.com/media.m4s?a=1&b=2")
                .put("width", audio ? 0 : 1920).put("height", audio ? 0 : 1080)
                .put("frameRate", "30000/1001")
                .put("SegmentBase", new JSONObject().put("Initialization", "0-733").put("indexRange", "734-2000"));
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static void testServer() throws Exception {
        LocalServer server = LocalServer.get();
        server.clear();
        byte[] manifest = "<MPD/>".getBytes(StandardCharsets.UTF_8);
        String address = server.put(manifest, "application/dash+xml");
        URL url = new URL(address);
        check("127.0.0.1".equals(url.getHost()), "Server must advertise loopback only");
        check(url.getPath().endsWith(".mpd"), "Legacy TVBox requires .mpd URL detection");
        manifest[0] = '!';
        HttpURLConnection connection = request(address, "GET");
        check(connection.getResponseCode() == 200, "Manifest unavailable");
        check("application/dash+xml".equals(connection.getContentType()), "DASH MIME missing");
        check("<MPD/>".equals(read(connection.getInputStream())), "Stored data must be isolated from caller mutation");
        connection.disconnect();

        connection = request(address, "HEAD");
        check(connection.getResponseCode() == 200 && connection.getContentLength() == 6, "HEAD metadata invalid");
        check(read(connection.getInputStream()).isEmpty(), "HEAD sent a body");
        connection.disconnect();

        connection = request(address, "POST");
        check(connection.getResponseCode() == 405, "Mutation method accepted");
        connection.disconnect();
        connection = request(address + "?url=https://example.com/", "GET");
        check(connection.getResponseCode() == 404, "Query route must not forward media");
        connection.disconnect();

        String expiring = server.put(new byte[]{1}, "image/png", 1);
        Thread.sleep(10);
        connection = request(expiring, "GET");
        check(connection.getResponseCode() == 404, "Expired login QR remained available");
        connection.disconnect();
        for (int index = 0; index < 64; index++) server.put(new byte[]{1}, "image/png");
        connection = request(address, "GET");
        check(connection.getResponseCode() == 404, "Store failed to evict oldest entry");
        connection.disconnect();
        String removable = server.put(new byte[]{1}, "image/png");
        server.remove(removable);
        connection = request(removable, "GET");
        check(connection.getResponseCode() == 404, "Login QR could not be invalidated");
        connection.disconnect();
        server.clear();
    }

    private static HttpURLConnection request(String url, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestMethod(method);
        return connection;
    }

    private static String read(InputStream input) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally { input.close(); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
