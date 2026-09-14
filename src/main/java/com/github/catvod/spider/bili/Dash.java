package com.github.catvod.spider.bili;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Converts the authorized playurl response into a static ISO-BMFF DASH manifest. */
public final class Dash {
    private Dash() { }

    public static String create(JSONObject dash) throws JSONException {
        return create(dash, 80);
    }

    /** Selects the closest quality at or below the request, then a compatible codec. */
    public static String create(JSONObject dash, int preferredQuality) throws JSONException {
        return create(dash, preferredQuality, false);
    }

    /** Emits only the requested quality; never silently substitutes a different resolution. */
    public static String createExact(JSONObject dash, int quality) throws JSONException {
        return create(dash, quality, true);
    }

    /** Available qualities, highest first, with the same track validation used by createExact. */
    public static List<Integer> availableQualities(JSONObject dash) {
        List<Integer> result = new ArrayList<>();
        if (dash == null || !finitePositive(dash.optDouble("duration", 0))
                || selectAudio(dash.optJSONArray("audio")) == null) return result;
        JSONArray values = dash.optJSONArray("video");
        if (values == null) return result;
        Set<Integer> qualities = new TreeSet<>(Collections.reverseOrder());
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (usable(value, "video")) qualities.add(value.optInt("id", 0));
        }
        result.addAll(qualities);
        return result;
    }

    private static String create(JSONObject dash, int preferredQuality, boolean exact) throws JSONException {
        if (dash == null) throw new JSONException("播放接口没有返回 DASH 数据");
        double duration = dash.optDouble("duration", 0);
        if (!finitePositive(duration)) throw new JSONException("DASH 时长无效");
        double buffer = dash.optDouble("minBufferTime", dash.optDouble("min_buffer_time", 1.5));
        if (!finitePositive(buffer)) buffer = 1.5;
        JSONObject video = selectVideo(dash.optJSONArray("video"), preferredQuality, exact);
        JSONObject audio = selectAudio(dash.optJSONArray("audio"));
        if (video == null) throw new JSONException(exact
                ? "当前账号没有所选清晰度的视频轨道：" + preferredQuality : "当前账号没有可播放的视频轨道");
        if (audio == null) throw new JSONException("播放接口没有返回可用的音频轨道");
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\"")
                .append(" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\"")
                .append(" mediaPresentationDuration=\"PT").append(decimal(duration)).append("S\"")
                .append(" minBufferTime=\"PT").append(decimal(buffer)).append("S\">\n")
                .append("<Period start=\"PT0S\" duration=\"PT").append(decimal(duration)).append("S\">\n");
        representation(xml, video, "video");
        representation(xml, audio, "audio");
        return xml.append("</Period>\n</MPD>\n").toString();
    }

    private static JSONObject selectVideo(JSONArray values, int preferred, boolean exact) {
        if (values == null || values.length() == 0) return null;
        JSONObject best = null;
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (!usable(value, "video") || (exact && value.optInt("id", 0) != preferred)) continue;
            if (best == null || betterQuality(value, best, preferred)) best = value;
        }
        return best;
    }

    private static boolean betterQuality(JSONObject value, JSONObject best, int preferred) {
        int quality = value.optInt("id", 0);
        int previous = best.optInt("id", 0);
        if (quality == previous) {
            int codec = codecPreference(value);
            int previousCodec = codecPreference(best);
            return codec < previousCodec || (codec == previousCodec
                    && value.optLong("bandwidth", 0) > best.optLong("bandwidth", 0));
        }
        if (preferred <= 0) return quality > previous;
        boolean lower = quality <= preferred;
        boolean previousLower = previous <= preferred;
        if (lower != previousLower) return lower;
        return lower ? quality > previous : quality < previous;
    }

    private static JSONObject selectAudio(JSONArray values) {
        if (values == null) return null;
        JSONObject best = null;
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (!usable(value, "audio")) continue;
            boolean aac = value.optString("codecs", "").startsWith("mp4a");
            boolean previousAac = best != null && best.optString("codecs", "").startsWith("mp4a");
            if (best == null || (aac && !previousAac)
                    || (aac == previousAac && value.optLong("bandwidth", 0) > best.optLong("bandwidth", 0)))
                best = value;
        }
        return best;
    }

    private static int codecPreference(JSONObject value) {
        int id = value.optInt("codecid", 0);
        String codec = value.optString("codecs", "");
        if (id == 7 || codec.startsWith("avc")) return 0;
        if (id == 12 || codec.startsWith("hev1") || codec.startsWith("hvc1")) return 1;
        if (id == 13 || codec.startsWith("av01")) return 2;
        return 3;
    }

    private static boolean usable(JSONObject value, String type) {
        if (value == null || value.optInt("id", 0) <= 0 || value.optLong("bandwidth", 0) <= 0
                || value.optString("codecs", "").isEmpty())
            return false;
        JSONObject segment = value.optJSONObject("SegmentBase");
        if (segment == null) segment = value.optJSONObject("segment_base");
        if (segment == null || !validRange(field(segment, "Initialization", "initialization"))
                || !validRange(field(segment, "indexRange", "index_range"))) return false;
        String mime = field(value, "mimeType", "mime_type");
        if (!mime.isEmpty() && !mime.equals(type + "/mp4")) return false;
        try {
            escape(mediaUrl(value));
            escape(value.optString("codecs", ""));
            return true;
        } catch (JSONException error) { return false; }
    }

    private static String mediaUrl(JSONObject value) throws JSONException {
        String url = field(value, "baseUrl", "base_url");
        if (url.startsWith("//")) url = "https:" + url;
        if (url.startsWith("http://")) url = "https://" + url.substring(7);
        try {
            URI parsed = new URI(url);
            if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null
                    || parsed.getUserInfo() != null) throw new URISyntaxException(url, "Expected HTTPS media URL");
        } catch (URISyntaxException error) {
            throw new JSONException("DASH 媒体地址格式无效");
        }
        return url;
    }

    private static void representation(StringBuilder xml, JSONObject value, String type) throws JSONException {
        JSONObject segment = value.optJSONObject("SegmentBase");
        if (segment == null) segment = value.optJSONObject("segment_base");
        String url = mediaUrl(value);
        String mime = field(value, "mimeType", "mime_type");
        if (mime.isEmpty()) mime = type + "/mp4";
        if (!mime.equals(type + "/mp4")) throw new JSONException("DASH 媒体类型不受支持");
        xml.append("<AdaptationSet contentType=\"").append(type).append("\" mimeType=\"")
                .append(escape(mime)).append("\" startWithSAP=\"1\">\n")
                .append("<Representation id=\"").append(type).append('_').append(value.optInt("id", 0))
                .append("\" bandwidth=\"").append(value.optLong("bandwidth", 0))
                .append("\" codecs=\"").append(escape(value.optString("codecs"))).append('"');
        if ("video".equals(type)) {
            dimension(xml, "width", value.optInt("width", 0));
            dimension(xml, "height", value.optInt("height", 0));
            String frameRate = field(value, "frameRate", "frame_rate");
            if (frameRate.matches("[0-9]+(?:\\.[0-9]+|/[1-9][0-9]*)?"))
                xml.append(" frameRate=\"").append(frameRate).append('"');
        } else {
            dimension(xml, "audioSamplingRate", value.optInt("audioSamplingRate", 0));
        }
        xml.append(">\n<BaseURL>").append(escape(url)).append("</BaseURL>\n")
                .append("<SegmentBase indexRange=\"").append(field(segment, "indexRange", "index_range"))
                .append("\" indexRangeExact=\"true\">\n<Initialization range=\"")
                .append(field(segment, "Initialization", "initialization"))
                .append("\"/>\n</SegmentBase>\n</Representation>\n</AdaptationSet>\n");
    }

    private static void dimension(StringBuilder xml, String key, int value) {
        if (value > 0) xml.append(' ').append(key).append("=\"").append(value).append('"');
    }

    private static String field(JSONObject value, String camel, String snake) {
        String result = value.optString(camel, "");
        return result.isEmpty() ? value.optString(snake, "") : result;
    }

    private static boolean validRange(String value) {
        if (!value.matches("[0-9]+-[0-9]+")) return false;
        try {
            String[] parts = value.split("-");
            return Long.parseLong(parts[0]) <= Long.parseLong(parts[1]);
        } catch (NumberFormatException error) { return false; }
    }

    private static boolean finitePositive(double value) {
        return value > 0 && !Double.isInfinite(value) && !Double.isNaN(value);
    }

    private static String decimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String escape(String value) throws JSONException {
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char letter = value.charAt(index);
            if ((letter < 32 && letter != 9 && letter != 10 && letter != 13)
                    || letter == 0xfffe || letter == 0xffff)
                throw new JSONException("DASH 数据包含无效的 XML 字符");
            switch (letter) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&apos;"); break;
                default: out.append(letter);
            }
        }
        return out.toString();
    }
}
