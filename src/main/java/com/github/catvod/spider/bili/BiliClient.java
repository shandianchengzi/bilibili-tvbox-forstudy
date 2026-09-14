package com.github.catvod.spider.bili;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/** Bilibili's read-only web API. Session credentials never leave approved API hosts. */
public final class BiliClient {
    public static final String API = "https://api.bilibili.com";
    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String PASSPORT = "https://passport.bilibili.com";
    private static final Object COOKIE_LOCK = new Object();
    private static long sessionEpoch;
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "api.bilibili.com", "www.bilibili.com", "passport.bilibili.com",
            "passport.biligame.com"));
    private static final Set<String> COOKIE_NAMES = new HashSet<>(Arrays.asList(
            "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5",
            "sid", "buvid3", "buvid4", "b_nut"));
    private static final Set<String> AUTH_NAMES = new HashSet<>(Arrays.asList(
            "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid"));
    private static final int[] MIXIN = {46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,
            27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13};
    private static final ConnectionFactory NETWORK = new ConnectionFactory() {
        public HttpURLConnection open(URL url) throws IOException {
            return (HttpURLConnection) url.openConnection();
        }
    };
    private final SharedPreferences preferences;
    private final ConnectionFactory connections;
    private String mixinKey = "";
    private long mixinExpires;
    private long visitorAttempt;
    private String qrKey = "";
    private long qrEpoch;
    private JSONObject qrSuccess;
    private boolean qrVerified;
    private final Map<String, StoredCookie> qrCookies = new LinkedHashMap<>();

    public BiliClient(Context context) {
        if (context == null) throw new IllegalArgumentException("缺少 Android Context");
        preferences = context.getApplicationContext().getSharedPreferences(
                "bilibili_tvbox_forstudy_session_v1", Context.MODE_PRIVATE);
        connections = NETWORK;
    }

    BiliClient(SharedPreferences preferences) {
        this(preferences, NETWORK);
    }

    /** Package-local transport seam for complete login transactions without real credentials. */
    BiliClient(SharedPreferences preferences, ConnectionFactory connections) {
        if (preferences == null || connections == null) throw new IllegalArgumentException("缺少登录依赖");
        this.preferences = preferences;
        this.connections = connections;
    }

    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    /** Local session presence, not a claim that a server-side session is still valid. */
    public boolean hasSession() { return !cookieValue("SESSDATA").isEmpty(); }
    public boolean isLoggedIn() { return hasSession(); }
    public String userId() { return cookieValue("DedeUserID"); }
    public JSONObject userInfo() throws Exception { return get("/x/web-interface/nav", null); }

    /** For internal API use only. Never attach this to playback or image requests. */
    public String getCookie() {
        synchronized (COOKIE_LOCK) {
            return cookieHeader(loadCookies());
        }
    }

    public static Map<String, String> playbackHeaders() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("User-Agent", UA);
        result.put("Referer", "https://www.bilibili.com/");
        return result;
    }

    /** Removes this source's device-local credentials, without changing the account on Bilibili. */
    public void logout() {
        synchronized (COOKIE_LOCK) {
            sessionEpoch++;
            preferences.edit().remove("cookies").commit();
        }
    }

    /** Cancel pending QR work immediately while keeping the last verified account. */
    public void cancelQr() {
        // Do not acquire this client's monitor: pollQr may be waiting on the network.
        synchronized (COOKIE_LOCK) { sessionEpoch++; }
    }

    public JSONObject get(String path, Map<String, String> params) throws Exception {
        return payload(getRaw(path, params));
    }

    /** Checked envelope; use this for endpoints whose data value is an array. */
    public JSONObject getRaw(String path, Map<String, String> params) throws Exception {
        if (path == null || path.indexOf('?') >= 0 || path.indexOf('#') >= 0)
            throw new IOException("接口地址格式错误");
        String target = path.startsWith("/") ? API + path : path;
        URL url = checkedUrl(target);
        if ("passport.biligame.com".equalsIgnoreCase(url.getHost()))
            throw new IOException("登录票据地址仅能用于扫码登录");
        boolean signed = url.getPath().contains("/wbi/");
        Map<String, String> query = copy(params);
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, String> actual = signed ? sign(query, wbiKey(attempt > 0)) : query;
            JSONObject envelope = json(request(withQuery(target, actual), null, epoch(), false));
            if (signed && envelope.optInt("code", -1) == -403 && attempt == 0) continue;
            check(envelope);
            return envelope;
        }
        throw new IOException("Bilibili 签名验证失败，请稍后重试");
    }

    public JSONObject search(String keyword, int page, String order) throws Exception {
        return search(keyword, page, order, "0");
    }

    public JSONObject search(String keyword, int page, String order, String duration) throws Exception {
        ensureVisitor();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("keyword", keyword == null ? "" : keyword.trim());
        params.put("search_type", "video");
        params.put("page", String.valueOf(Math.max(1, page)));
        params.put("order", Arrays.asList("totalrank", "click", "pubdate", "dm", "stow").contains(order)
                ? order : "totalrank");
        params.put("duration", Arrays.asList("0", "1", "2", "3", "4").contains(duration) ? duration : "0");
        return get("/x/web-interface/wbi/search/type", params);
    }

    /** Search licensed shows without including uploader videos in the film/TV source. */
    public JSONObject searchMedia(String kind, String keyword, int page) throws Exception {
        if (!"media_ft".equals(kind) && !"media_bangumi".equals(kind))
            throw new IllegalArgumentException("不支持的影视搜索类型");
        ensureVisitor();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("search_type", kind);
        params.put("keyword", keyword == null ? "" : keyword.trim());
        params.put("page", String.valueOf(Math.max(1, page)));
        return get("/x/web-interface/wbi/search/type", params);
    }

    /** Call off the main thread; display the returned URL as a QR image locally. */
    public synchronized JSONObject beginQr() throws Exception {
        qrKey = "";
        qrSuccess = null;
        qrVerified = false;
        qrCookies.clear();
        qrEpoch = epoch();
        JSONObject envelope = json(request(PASSPORT + "/x/passport-login/web/qrcode/generate",
                qrCookies, qrEpoch, false));
        check(envelope);
        JSONObject result = payload(envelope);
        String key = result.optString("qrcode_key");
        String url = result.optString("url");
        if (key.isEmpty() || url.isEmpty()) throw new IOException("Bilibili 未返回登录二维码");
        checkedScanUrl(url);
        qrKey = key;
        return new JSONObject().put("url", url).put("qrcode_key", key);
    }

    /** 0 = verified session; 86101 = waiting scan; 86090 = waiting confirm; 86038 = expired. */
    public synchronized int pollQr(String key) throws Exception {
        if (key == null || key.isEmpty() || !key.equals(qrKey) || qrEpoch != epoch())
            return 86038;
        if (qrVerified) return 0;
        if (qrSuccess == null) {
            JSONObject envelope = json(request(withQuery(PASSPORT
                    + "/x/passport-login/web/qrcode/poll", Collections.singletonMap("qrcode_key", key)),
                    qrCookies, qrEpoch, false));
            check(envelope);
            JSONObject status = payload(envelope);
            int code = status.optInt("code", -1);
            if (code == 86101 || code == 86090 || code == 86038) return code;
            if (code != 0) throw new ApiException(code);
            // Save before following the ticket: the successful key can only be polled once.
            qrSuccess = status;
        }
        String credentialUrl = qrSuccess.optString("url");
        if (!hasValidSession(qrCookies) && !credentialUrl.isEmpty()) {
            URL url = checkedUrl(credentialUrl);
            readLegacyCredentials(url, qrCookies);
            if (!hasValidSession(qrCookies)) {
                if (!isPassport(url) || !url.getPath().equals("/x/passport-login/web/crossDomain"))
                    throw new IOException("Bilibili 返回了不支持的登录票据地址");
                request(credentialUrl, qrCookies, qrEpoch, true);
            }
        }
        if (!hasValidSession(qrCookies))
            throw new IOException("扫码确认成功，但未获取有效登录凭证，请重新扫码");
        if (Thread.currentThread().isInterrupted()) return 86038;

        // Verify this candidate in its own jar. A rejected or interrupted login must not
        // replace a working account, and a transient failure can retry without polling again.
        JSONObject envelope = json(request(API + "/x/web-interface/nav", qrCookies, qrEpoch, false));
        check(envelope);
        JSONObject nav = payload(envelope);
        if (!nav.optBoolean("isLogin", false)) throw new ApiException(-101);
        if (!hasValidSession(qrCookies)) throw new ApiException(-101);
        StoredCookie session = qrCookies.get("SESSDATA");
        String uid = nav.optString("mid", "");
        if (validCookie("DedeUserID", uid))
            qrCookies.put("DedeUserID", new StoredCookie(uid, session.expires));
        synchronized (COOKIE_LOCK) {
            if (qrEpoch != sessionEpoch || Thread.currentThread().isInterrupted()) return 86038;
            Map<String, StoredCookie> existing = loadCookies();
            // Prevent old-account identity fields from surviving an account switch.
            for (String name : AUTH_NAMES) existing.remove(name);
            existing.putAll(qrCookies);
            saveCookies(existing);
            qrVerified = true;
        }
        return qrEpoch == epoch() ? 0 : 86038;
    }

    private synchronized String wbiKey(boolean force) throws Exception {
        if (!force && !mixinKey.isEmpty() && System.currentTimeMillis() < mixinExpires) return mixinKey;
        JSONObject nav = json(request(API + "/x/web-interface/nav", null, epoch(), false));
        // The anonymous nav response has code -101 but still contains usable public WBI keys.
        int code = nav.optInt("code", -1);
        if (code != 0 && code != -101) throw new ApiException(code);
        JSONObject data = nav.optJSONObject("data");
        JSONObject images = data == null ? null : data.optJSONObject("wbi_img");
        if (images == null) throw new IOException("无法获取 Bilibili 搜索签名，请稍后重试");
        String original = stem(images.optString("img_url")) + stem(images.optString("sub_url"));
        if (original.length() < 64) throw new IOException("Bilibili 签名数据不完整");
        StringBuilder key = new StringBuilder(32);
        for (int index : MIXIN) key.append(original.charAt(index));
        mixinKey = key.toString();
        mixinExpires = System.currentTimeMillis() + 3600000L;
        return mixinKey;
    }

    /** Deterministic parameter canonicalization used by the current Bilibili web client. */
    public static Map<String, String> sign(Map<String, String> params, String mixinKey) throws Exception {
        return signAt(params, mixinKey, System.currentTimeMillis() / 1000L);
    }

    static Map<String, String> signAt(Map<String, String> params, String mixinKey, long timestamp) throws Exception {
        TreeMap<String, String> signed = new TreeMap<>();
        if (params != null) for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getKey().equals("w_rid"))
                signed.put(entry.getKey(), entry.getValue().replaceAll("[!'()*]", ""));
        }
        signed.put("wts", String.valueOf(timestamp));
        byte[] digest = MessageDigest.getInstance("MD5").digest(
                (queryString(signed) + mixinKey).getBytes(StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder(32);
        for (byte value : digest) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
        signed.put("w_rid", hash.toString());
        return signed;
    }

    private synchronized void ensureVisitor() throws Exception {
        if (!cookieValue("buvid3").isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - visitorAttempt < 600000L) return;
        visitorAttempt = now;
        long currentEpoch = epoch();
        JSONObject visitor = get("/x/frontend/finger/spi", null);
        for (String name : Arrays.asList("buvid3", "buvid4")) {
            String value = visitor.optString(name.equals("buvid3") ? "b_3" : "b_4");
            if (validCookie(name, value)) putCookie(name, new StoredCookie(value, 0), currentEpoch);
        }
    }

    private String request(String target, Map<String, StoredCookie> loginCapture,
                           long requestEpoch, boolean crossDomain) throws Exception {
        URL url = checkedUrl(target);
        for (int hop = 0; hop < 6; hop++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("请求已取消");
            HttpURLConnection connection = null;
            try {
                connection = connections.open(url);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent", UA);
                connection.setRequestProperty("Referer", "https://www.bilibili.com/");
                connection.setRequestProperty("Accept", "application/json, text/plain, */*");
                connection.setRequestProperty("Accept-Encoding", "gzip");
                // The biligame ticket exchange deliberately receives no existing Bilibili cookie.
                if (!url.getHost().equalsIgnoreCase("passport.biligame.com"))
                    connection.setRequestProperty("Cookie", loginCapture == null ? getCookie() : cookieHeader(loginCapture));
                else connection.setRequestProperty("Cookie", "");
                int status = connection.getResponseCode();
                captureCookies(url, connection.getHeaderFields(), loginCapture, requestEpoch, crossDomain);
                // A ticket exchange is complete once credentials arrive. Its browser landing
                // page can redirect elsewhere or fail; neither should discard this login.
                if (crossDomain && status >= 200 && status < 400 && hasValidSession(loginCapture))
                    return "";
                if (status >= 300 && status <= 399) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Bilibili 重定向缺少目标地址");
                    url = checkedUrl(new URL(url, location).toExternalForm());
                    if (url.getHost().equalsIgnoreCase("passport.biligame.com") && !crossDomain)
                        throw new IOException("不支持的 Bilibili 重定向");
                    continue;
                }
                if (status < 200 || status >= 300)
                    throw new IOException(status == 412 || status == 429
                            ? "Bilibili 暂时限制访问，请稍后重试（HTTP " + status + "）"
                            : "Bilibili 请求失败（HTTP " + status + "）");
                InputStream stream = connection.getInputStream();
                if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) stream = new GZIPInputStream(stream);
                try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("请求已取消");
                        if (output.size() + count > 8 * 1024 * 1024) throw new IOException("Bilibili 响应过大");
                        output.write(buffer, 0, count);
                    }
                    return new String(output.toByteArray(), StandardCharsets.UTF_8);
                }
            } catch (java.net.SocketTimeoutException timeout) {
                throw new IOException("Bilibili 请求超时，请检查网络后重试");
            } catch (IOException failure) {
                // Do not include URL-bearing transport messages (QR URLs carry login tickets).
                if (failure.getMessage() != null && (failure.getMessage().startsWith("Bilibili")
                        || failure.getMessage().startsWith("不支持") || failure.getMessage().equals("请求已取消")))
                    throw failure;
                throw new IOException("无法连接 Bilibili，请检查网络后重试");
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw new IOException("Bilibili 重定向次数过多");
    }

    private void captureCookies(URL origin, Map<String, List<String>> headers,
                                Map<String, StoredCookie> loginCapture, long requestEpoch,
                                boolean crossDomain) {
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (!"set-cookie".equalsIgnoreCase(header.getKey()) || header.getValue() == null) continue;
            for (String raw : header.getValue()) {
                try {
                    for (HttpCookie cookie : HttpCookie.parse(raw)) {
                        String name = cookie.getName();
                        String value = cookie.getValue();
                        if (!validCookie(name, value) && cookie.getMaxAge() != 0) continue;
                        if (!COOKIE_NAMES.contains(name)) continue;
                        String domain = cookie.getDomain();
                        if (domain != null) {
                            domain = domain.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
                            String host = origin.getHost().toLowerCase(Locale.ROOT);
                            // This explicit passport ticket exchange is a credential transfer,
                            // including Bilibili cookies carried by the Biligame passport host.
                            boolean ticketTransfer = crossDomain && host.equals("passport.biligame.com")
                                    && domain.equals("bilibili.com");
                            if (!host.equals(domain) && !host.endsWith("." + domain) && !ticketTransfer) continue;
                            if (!domain.equals("bilibili.com") && !domain.endsWith(".bilibili.com")
                                    && !domain.equals("biligame.com") && !domain.endsWith(".biligame.com")) continue;
                        }
                        if (origin.getHost().equalsIgnoreCase("passport.biligame.com") && !crossDomain) continue;
                        long age = cookie.getMaxAge();
                        long expires = age < 0 ? 0 : System.currentTimeMillis() + Math.min(age, 315360000L) * 1000L;
                        StoredCookie stored = new StoredCookie(value, expires);
                        if (loginCapture != null) {
                            if (age == 0) loginCapture.remove(name); else loginCapture.put(name, stored);
                        } else putCookie(name, age == 0 ? null : stored, requestEpoch);
                    }
                } catch (IllegalArgumentException ignored) { /* Ignore malformed server cookies. */ }
            }
        }
    }

    private static void readLegacyCredentials(URL url, Map<String, StoredCookie> capture) throws Exception {
        if (!isPassport(url)) return;
        String query = url.getQuery();
        if (query == null) return;
        for (String item : query.split("&")) {
            int equals = item.indexOf('=');
            if (equals < 1) continue;
            String name = URLDecoder.decode(item.substring(0, equals), "UTF-8");
            // SESSDATA cookies conventionally retain percent escapes from the login URL.
            String value = item.substring(equals + 1);
            if (!name.equals("SESSDATA")) value = URLDecoder.decode(value, "UTF-8");
            if (AUTH_NAMES.contains(name) && validCookie(name, value))
                capture.put(name, new StoredCookie(value, 0));
        }
    }

    private Map<String, StoredCookie> loadCookies() {
        Map<String, StoredCookie> result = new LinkedHashMap<>();
        try {
            JSONObject saved = new JSONObject(preferences.getString("cookies", "{}"));
            Iterator<String> keys = saved.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                JSONObject entry = saved.optJSONObject(name);
                if (entry == null) continue;
                StoredCookie cookie = new StoredCookie(entry.optString("value"), entry.optLong("expires"));
                if (validCookie(name, cookie.value) && !cookie.expired()) result.put(name, cookie);
            }
        } catch (Exception ignored) { /* A damaged local session is treated as signed out. */ }
        return result;
    }

    private void saveCookies(Map<String, StoredCookie> cookies) {
        try {
            JSONObject saved = new JSONObject();
            for (Map.Entry<String, StoredCookie> entry : cookies.entrySet()) {
                if (!entry.getValue().expired()) saved.put(entry.getKey(), new JSONObject()
                        .put("value", entry.getValue().value).put("expires", entry.getValue().expires));
            }
            if (!preferences.edit().putString("cookies", saved.toString()).commit())
                throw new IllegalStateException("无法保存本机登录状态");
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("无法保存本机登录状态");
        }
    }

    private void putCookie(String name, StoredCookie cookie, long requestEpoch) {
        synchronized (COOKIE_LOCK) {
            if (requestEpoch != sessionEpoch) return;
            Map<String, StoredCookie> cookies = loadCookies();
            if (cookie == null) cookies.remove(name); else cookies.put(name, cookie);
            saveCookies(cookies);
        }
    }

    private String cookieValue(String name) {
        synchronized (COOKIE_LOCK) {
            StoredCookie value = loadCookies().get(name);
            return value == null ? "" : value.value;
        }
    }

    private static boolean validCookie(String name, String value) {
        if (!COOKIE_NAMES.contains(name) || value == null || value.isEmpty() || value.length() > 8192) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 32 || c >= 127 || c == ';' || c == '\\' || c == '"') return false;
        }
        return true;
    }

    private static boolean hasValidSession(Map<String, StoredCookie> cookies) {
        StoredCookie session = cookies == null ? null : cookies.get("SESSDATA");
        return session != null && !session.expired() && validCookie("SESSDATA", session.value);
    }

    private static String cookieHeader(Map<String, StoredCookie> cookies) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, StoredCookie> entry : cookies.entrySet()) {
            if (entry.getValue().expired()) continue;
            if (result.length() > 0) result.append("; ");
            result.append(entry.getKey()).append('=').append(entry.getValue().value);
        }
        return result.toString();
    }

    private static long epoch() { synchronized (COOKIE_LOCK) { return sessionEpoch; } }
    private static boolean isPassport(URL url) {
        return url.getHost().equalsIgnoreCase("passport.bilibili.com") || url.getHost().equalsIgnoreCase("passport.biligame.com");
    }

    /** QR content is displayed for the phone; it is never an authenticated API request. */
    private static URL checkedScanUrl(String value) throws IOException {
        try {
            URL url = new URL(value);
            String host = url.getHost().toLowerCase(Locale.ROOT);
            String path = url.getPath();
            boolean current = host.equals("account.bilibili.com") && path.equals("/h5/account-h5/auth/scan-web");
            boolean legacy = host.equals("passport.bilibili.com")
                    && (path.equals("/h5-app/passport/login/scan") || path.equals("/qrcode/h5/login"));
            if (!url.getProtocol().equals("https") || (!current && !legacy)
                    || url.getUserInfo() != null || url.getRef() != null
                    || (url.getPort() != -1 && url.getPort() != 443))
                throw new IOException("不支持的 Bilibili 扫码地址");
            return url;
        } catch (java.net.MalformedURLException invalid) {
            throw new IOException("不支持的 Bilibili 扫码地址");
        }
    }

    private static URL checkedUrl(String value) throws IOException {
        try {
            URL url = new URL(value);
            if (!url.getProtocol().equals("https") || !HOSTS.contains(url.getHost().toLowerCase(Locale.ROOT))
                    || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443))
                throw new IOException("不支持的 Bilibili 接口地址");
            return url;
        } catch (java.net.MalformedURLException invalid) {
            throw new IOException("不支持的 Bilibili 接口地址");
        }
    }
    private static JSONObject json(String body) throws IOException {
        try { return new JSONObject(body); }
        catch (Exception invalid) { throw new IOException("Bilibili 返回了非 JSON 数据，请稍后重试"); }
    }
    private static void check(JSONObject envelope) throws ApiException {
        int code = envelope.optInt("code", Integer.MIN_VALUE);
        if (code != 0) throw new ApiException(code);
    }
    private static JSONObject payload(JSONObject envelope) throws IOException {
        JSONObject result = envelope.optJSONObject("data");
        if (result == null) result = envelope.optJSONObject("result");
        if (result == null) throw new IOException("Bilibili 返回数据格式已变化");
        return result;
    }
    private static String stem(String value) {
        int slash = value.lastIndexOf('/');
        int dot = value.indexOf('.', slash + 1);
        return dot > slash ? value.substring(slash + 1, dot) : "";
    }
    private static Map<String, String> copy(Map<String, String> params) {
        return params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
    }
    private static String withQuery(String target, Map<String, String> params) throws Exception {
        String query = queryString(params);
        return query.isEmpty() ? target : target + "?" + query;
    }
    private static String queryString(Map<String, String> params) throws Exception {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            if (result.length() > 0) result.append('&');
            result.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return result.toString();
    }
    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~").replace("*", "%2A");
    }
    private static final class StoredCookie {
        final String value;
        final long expires;
        StoredCookie(String value, long expires) { this.value = value; this.expires = expires; }
        boolean expired() { return expires > 0 && expires <= System.currentTimeMillis(); }
    }
    public static final class ApiException extends IOException {
        public final int code;
        public ApiException(int code) {
            super(code == -101 ? "请先扫码登录 Bilibili" : code == -412 || code == -352
                    ? "Bilibili 暂时限制访问，请稍后重试（" + code + "）"
                    : "Bilibili 接口返回错误（" + code + "）");
            this.code = code;
        }
    }
}
