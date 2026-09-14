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
import javax.xml.parsers.DocumentBuilderFactory;

/** JVM integration checks; requires a real org.json jar, never Android's throwing stubs. */
public final class DashLocalServerTest {
    private static final String NS = "urn:mpeg:dash:schema:mpd:2011";

    public static void main(String[] arguments) throws Exception {
        testManifest();
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
        check("video_80".equals(video.getAttribute("id")), "AVC preference failed");
        check("audio_30280".equals(audio.getAttribute("id")), "AAC preference/bitrate failed");
        check("avc1.640028".equals(video.getAttribute("codecs")), "Video codecs missing");
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
