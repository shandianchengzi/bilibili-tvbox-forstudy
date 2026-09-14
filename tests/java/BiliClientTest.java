package com.github.catvod.spider.bili;

import android.content.SharedPreferences;
import com.github.catvod.spider.BiliStudy;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline wire-contract/security regressions; no Android methods or live account required. */
public final class BiliClientTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        wbiPublishedVector();
        wbiUnicodeAndFreshTimestamp();
        cookieFreePlaybackHeaders();
        endpointHostBoundary();
        scanPayloadBoundary();
        cookieHeaderValidation();
        legacyQrCookies();
        directQrTransaction();
        legacyQrTransaction();
        ticketQrTransaction();
        retryCandidateVerification();
        rejectedCandidatePreservesAccount();
        cancelDuringVerification();
        logoutDuringVerification();
        playerQualityTransactions();
        detailQualityProbe();
        mediaRecommendationTransactions();
        if ("1".equals(System.getenv("BILI_QR_LIVE_SMOKE"))) liveQrSmoke();
        if ("1".equals(System.getenv("BILI_PLAYBACK_LIVE_SMOKE"))) reportLivePlaybackSmoke();
        System.out.println("BiliClientTest: " + assertions + " assertions passed");
    }

    private static void wbiPublishedVector() throws Exception {
        // Public protocol example independently documented at:
        // https://github.com/pskdje/bilibili-API-collect/blob/main/docs/misc/sign/wbi.md
        String original = "7cd084941338484aae1ad9425b84077c4932caff0ff746eab6f01bf08b70ac45";
        Field tableField = BiliClient.class.getDeclaredField("MIXIN");
        tableField.setAccessible(true);
        int[] table = (int[]) tableField.get(null);
        StringBuilder mixin = new StringBuilder();
        for (int position : table) mixin.append(original.charAt(position));
        equal("ea1db124af3c7062474693fa704f4ff8", mixin.toString(), "public WBI permutation");
        Map<String, String> params = map("foo", "114", "bar", "514", "zab", "1919810");
        Map<String, String> signed = BiliClient.signAt(params, mixin.toString(), 1702204169L);
        equal("8f6f2b5b3d485fe1886cec6a0be8c5d4", signed.get("w_rid"), "public WBI digest");
        equal("1702204169", signed.get("wts"), "seconds timestamp");
        check(!params.containsKey("w_rid") && !params.containsKey("wts"), "original parameters untouched");
    }

    private static void wbiUnicodeAndFreshTimestamp() throws Exception {
        Map<String, String> params = map("foo", "one one four!()'*", "bar", "五一四", "baz", "1919810",
                "w_rid", "obsolete-signature", "wts", "123");
        Map<String, String> signed = BiliClient.signAt(params, "ea1db124af3c7062474693fa704f4ff8", 1702204169L);
        // Expected digest computed from a manually specified UTF-8/RFC3986 query using Python hashlib.
        equal("04e50b58980e3e3cee8cbc0cc4c1c530", signed.get("w_rid"), "Unicode, %20 spaces and special-character filtering");
        equal("one one four", signed.get("foo"), "signed wire value excludes Bilibili-filtered characters");
        equal("obsolete-signature", params.get("w_rid"), "retry never mutates caller query");
        equal("123", params.get("wts"), "caller timestamp unchanged");
        Map<String, String> current = BiliClient.sign(params, "ea1db124af3c7062474693fa704f4ff8");
        long timestamp = Long.parseLong(current.get("wts"));
        check(Math.abs(System.currentTimeMillis() / 1000L - timestamp) <= 2, "production timestamps use current seconds");
        check(!"obsolete-signature".equals(current.get("w_rid")), "stale signatures are replaced");
    }

    private static void cookieFreePlaybackHeaders() {
        Map<String, String> headers = BiliClient.playbackHeaders();
        check(headers.containsKey("User-Agent"), "media user-agent present");
        equal("https://www.bilibili.com/", headers.get("Referer"), "media Referer present");
        for (String name : headers.keySet()) check(!"cookie".equalsIgnoreCase(name), "no account cookies on media headers");
        headers.put("Cookie", "test");
        check(!BiliClient.playbackHeaders().containsKey("Cookie"), "media headers are independent per call");
    }

    private static void endpointHostBoundary() throws Exception {
        Method checked = method("checkedUrl", String.class);
        for (String address : new String[] {"https://api.bilibili.com/x/web-interface/nav",
                "https://passport.bilibili.com/x/passport-login/web/qrcode/poll",
                "https://PASSPORT.BILIGAME.COM/x/passport-login/web/crossDomain?ticket=test"}) {
            check(checked.invoke(null, address) instanceof URL, "approved API host accepted");
        }
        for (String address : new String[] {"http://api.bilibili.com/x", "https://api.bilibili.com.attacker.example/x",
                "https://api.bilibili.com@attacker.example/x", "https://attacker.example@api.bilibili.com/x",
                "https://api.bilibili.com:444/x", "https://127.0.0.1/x", "https://i0.hdslb.com/x",
                "https://bilibili.com/x", "https://account.bilibili.com/h5/account-h5/auth/scan-web", "file:///etc/passwd"}) {
            rejectsIo(checked, address);
        }
    }

    private static void scanPayloadBoundary() throws Exception {
        Method checked = method("checkedScanUrl", String.class);
        // The current HTTPS generate API returns an account.bilibili.com QR payload.
        // This address must be displayable without making it an approved credential host.
        for (String address : new String[] {
                "https://account.bilibili.com/h5/account-h5/auth/scan-web?navhide=1&callback=close&qrcode_key=synthetic-qr-key&from=",
                "https://passport.bilibili.com/h5-app/passport/login/scan?qrcode_key=synthetic-qr-key",
                "https://passport.bilibili.com/qrcode/h5/login?oauthKey=synthetic-qr-key"}) {
            Fixture fixture = new Fixture();
            fixture.generate(address);
            equal(address, fixture.client.beginQr().getString("url"), "current and historical official scan payloads preserved");
            fixture.complete();
        }
        for (String address : new String[] {
                "http://account.bilibili.com/h5/account-h5/auth/scan-web",
                "https://account.bilibili.com.attacker.example/h5/account-h5/auth/scan-web",
                "https://account.bilibili.com@attacker.example/h5/account-h5/auth/scan-web",
                "https://attacker.example@account.bilibili.com/h5/account-h5/auth/scan-web",
                "https://account.bilibili.com:444/h5/account-h5/auth/scan-web",
                "https://account.bilibili.com/h5/account-h5/auth/scan-web#untrusted",
                "https://account.bilibili.com/other",
                "https://passport.bilibili.com/x/passport-login/web/qrcode/poll",
                "https://passport.biligame.com/h5/account-h5/auth/scan-web",
                "file:///etc/passwd"}) {
            rejectsIo(checked, address);
        }
    }

    private static void cookieHeaderValidation() throws Exception {
        Method valid = method("validCookie", String.class, String.class);
        check((Boolean) valid.invoke(null, "SESSDATA", "abc%2C123%2Cxyz"), "percent-encoded session cookie accepted");
        for (String value : new String[] {"", "value;other=1", "x\r\nInjected: 1", "x y", "汉字", "x\\y", "\"value\""})
            check(!(Boolean) valid.invoke(null, "SESSDATA", value), "malformed cookie header rejected");
        check(!(Boolean) valid.invoke(null, "unrelated_tracking_cookie", "test"), "cookie names allowlisted");
    }

    private static void legacyQrCookies() throws Exception {
        Method legacy = method("readLegacyCredentials", URL.class, Map.class);
        Map<String, Object> capture = new LinkedHashMap<>();
        legacy.invoke(null, new URL("https://passport.bilibili.com/login?SESSDATA=abc%2Cxyz&bili_jct=jct&DedeUserID=42&redirect=attacker"), capture);
        equal(3, capture.size(), "legacy login imports only account fields");
        Field value = capture.get("SESSDATA").getClass().getDeclaredField("value");
        value.setAccessible(true);
        equal("abc%2Cxyz", value.get(capture.get("SESSDATA")), "SESSDATA escapes not double-decoded");
        Map<String, Object> rejected = new LinkedHashMap<>();
        legacy.invoke(null, new URL("https://attacker.example/login?SESSDATA=bad"), rejected);
        check(rejected.isEmpty(), "legacy credential URL must originate on passport host");
        legacy.invoke(null, new URL("https://passport.bilibili.com/login?SESSDATA=&bili_jct=bad%0D%0AHeader%3Avalue"), rejected);
        check(rejected.isEmpty(), "legacy malformed cookies rejected");
    }

    private static final String GENERATE = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate";
    private static final String POLL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=synthetic-qr-key";
    private static final String NAV = "https://api.bilibili.com/x/web-interface/nav";
    private static final String SYNTHETIC_SESSION = "synthetic-new%2Csession";

    private static void directQrTransaction() throws Exception {
        Fixture fixture = new Fixture();
        fixture.oldAccount();
        FakeConnection generate = fixture.generate().cookies("sid=synthetic-qr; Domain=.bilibili.com; Path=/");
        FakeConnection waiting = fixture.poll(86101, "").cookies("buvid3=synthetic-visitor; Domain=.bilibili.com; Path=/");
        FakeConnection scanned = fixture.poll(86090, "");
        FakeConnection success = fixture.poll(0, "").cookies(
                "SESSDATA=" + SYNTHETIC_SESSION + "; Domain=.bilibili.com; Max-Age=3600; Path=/; HttpOnly; Secure",
                "bili_jct=synthetic-jct; Domain=.bilibili.com; Path=/");
        FakeConnection nav = fixture.nav();
        nav.onResponse = () -> equal("synthetic-old", fixture.client.getCookie().split("SESSDATA=")[1].split(";")[0],
                "old account remains durable while candidate is verified");
        equal("synthetic-qr-key", fixture.client.beginQr().getString("qrcode_key"), "generated QR key returned");
        equal(86101, fixture.client.pollQr("synthetic-qr-key"), "waiting status preserved");
        equal(86090, fixture.client.pollQr("synthetic-qr-key"), "scanned status preserved");
        equal(0, fixture.client.pollQr("synthetic-qr-key"), "direct response cookies produce verified login");
        equal("", generate.getRequestProperty("Cookie"), "generate excludes prior account credentials");
        equal("sid=synthetic-qr", waiting.getRequestProperty("Cookie"), "generate cookie is reused by first poll");
        check(scanned.getRequestProperty("Cookie").contains("buvid3=synthetic-visitor"), "poll cookie is reused by later poll");
        check(!success.getRequestProperty("Cookie").contains("synthetic-old"), "pending login never sends old session");
        check(nav.getRequestProperty("Cookie").contains("SESSDATA=" + SYNTHETIC_SESSION), "nav verifies candidate cookie");
        check(fixture.client.getCookie().contains("SESSDATA=" + SYNTHETIC_SESSION), "verified cookie persisted without decoding");
        equal("42", fixture.client.userId(), "verified account UID persisted");
        check(!fixture.client.getCookie().contains("DedeUserID__ckMd5"), "old account identity fields are removed");
        equal(0, fixture.client.pollQr("synthetic-qr-key"), "already verified QR is idempotent");
        fixture.client.cancelQr();
        equal(86038, fixture.client.pollQr("synthetic-qr-key"), "canceled QR cannot reuse cached verified success");
        check(fixture.client.getCookie().contains("SESSDATA=" + SYNTHETIC_SESSION), "closing QR after success preserves verified account");
        fixture.complete();
    }

    private static void legacyQrTransaction() throws Exception {
        Fixture fixture = new Fixture();
        fixture.generate();
        fixture.poll(0, "https://passport.biligame.com/crossDomain?SESSDATA=" + SYNTHETIC_SESSION
                + "&bili_jct=synthetic-jct&DedeUserID=42&gourl=https%3A%2F%2Fwww.bilibili.com");
        FakeConnection nav = fixture.nav();
        fixture.client.beginQr();
        equal(0, fixture.client.pollQr("synthetic-qr-key"), "legacy URL credentials verify without landing request");
        check(nav.getRequestProperty("Cookie").contains("SESSDATA=" + SYNTHETIC_SESSION), "legacy SESSDATA escapes survive wire request");
        fixture.complete();
    }

    private static void ticketQrTransaction() throws Exception {
        // Both a redirecting ticket endpoint and a 200 endpoint may set credentials.
        for (int ticketStatus : new int[] {302, 200}) {
            Fixture fixture = new Fixture();
            fixture.oldAccount();
            fixture.generate().cookies("sid=synthetic-qr; Domain=.bilibili.com; Path=/");
            String ticket = "https://passport.biligame.com/x/passport-login/web/crossDomain?ticket=synthetic-ticket";
            fixture.poll(0, ticket);
            FakeConnection exchange = fixture.add(ticket, ticketStatus, "unused");
            // A Biligame hop must not receive existing Bilibili credentials.
            exchange.headers.put("Location", Arrays.asList("https://passport.bilibili.com/x/passport-login/web/crossDomain?ticket=synthetic-next"));
            exchange.status = 302;
            FakeConnection callback = fixture.add(exchange.headers.get("Location").get(0), ticketStatus, "unused").cookies(
                    "SESSDATA=" + SYNTHETIC_SESSION + "; Domain=.bilibili.com; Max-Age=3600; Path=/",
                    "bili_jct=synthetic-jct; Domain=.bilibili.com; Path=/");
            callback.headers.put("Location", Arrays.asList("https://unavailable.example/landing"));
            callback.bodyFailure = new IOException("synthetic landing body unavailable");
            fixture.nav();
            fixture.client.beginQr();
            equal(0, fixture.client.pollQr("synthetic-qr-key"), "ticket credentials survive unavailable landing/body");
            equal("", exchange.getRequestProperty("Cookie"), "Biligame exchange receives no existing cookie");
            equal("sid=synthetic-qr", callback.getRequestProperty("Cookie"), "Bilibili callback receives pending QR cookie jar");
            check(!callback.bodyRead, "ticket body is not read after valid credentials arrive");
            check(callback.disconnected, "completed ticket connection is released");
            fixture.complete();
        }

        Fixture transferred = new Fixture();
        transferred.generate();
        String ticket = "https://passport.biligame.com/x/passport-login/web/crossDomain?ticket=synthetic-direct";
        transferred.poll(0, ticket);
        transferred.add(ticket, 302, "unused").cookies(
                "SESSDATA=" + SYNTHETIC_SESSION + "; Domain=.bilibili.com; Path=/",
                "bili_jct=synthetic-jct; Domain=.biligame.com; Path=/");
        transferred.nav();
        transferred.client.beginQr();
        equal(0, transferred.client.pollQr("synthetic-qr-key"), "explicit Biligame ticket can transfer Bilibili cookies without Location");
        transferred.complete();
    }

    private static void retryCandidateVerification() throws Exception {
        Fixture fixture = new Fixture();
        fixture.oldAccount();
        fixture.generate();
        fixture.poll(0, "").cookies("SESSDATA=" + SYNTHETIC_SESSION + "; Domain=.bilibili.com; Path=/");
        FakeConnection timedOut = fixture.nav();
        timedOut.responseFailure = new SocketTimeoutException("synthetic timeout");
        fixture.nav();
        fixture.client.beginQr();
        try {
            fixture.client.pollQr("synthetic-qr-key");
            throw new AssertionError("nav timeout must be retryable");
        } catch (IOException expected) {
            check(!(expected instanceof BiliClient.ApiException), "transport failure remains distinguishable from invalid account");
        }
        check(fixture.client.getCookie().contains("SESSDATA=synthetic-old"), "transient verification failure preserves old account");
        equal(0, fixture.client.pollQr("synthetic-qr-key"), "nav verification retries without consuming QR key again");
        equal(0, fixture.client.pollQr("synthetic-qr-key"), "verified success needs no repeated nav or poll request");
        fixture.complete();
    }

    private static void rejectedCandidatePreservesAccount() throws Exception {
        for (String rejected : new String[] {"{\"code\":-101,\"data\":{\"isLogin\":false}}",
                "{\"code\":0,\"data\":{\"isLogin\":false}}"}) {
            Fixture fixture = new Fixture();
            fixture.oldAccount();
            String previous = fixture.client.getCookie();
            fixture.generate();
            fixture.poll(0, "").cookies("SESSDATA=synthetic-invalid; Domain=.bilibili.com; Path=/");
            fixture.add(NAV, 200, rejected);
            fixture.client.beginQr();
            try {
                fixture.client.pollQr("synthetic-qr-key");
                throw new AssertionError("invalid candidate must not log in");
            } catch (BiliClient.ApiException expected) {
                equal(-101, expected.code, "invalid candidate is explicitly rejected");
            }
            equal(previous, fixture.client.getCookie(), "rejected candidate cannot erase existing account");
            fixture.complete();
        }
    }

    private static void logoutDuringVerification() throws Exception {
        Fixture fixture = new Fixture();
        fixture.oldAccount();
        fixture.generate();
        fixture.poll(0, "").cookies("SESSDATA=" + SYNTHETIC_SESSION + "; Domain=.bilibili.com; Path=/");
        fixture.nav().onResponse = () -> fixture.client.logout();
        fixture.client.beginQr();
        equal(86038, fixture.client.pollQr("synthetic-qr-key"), "logout invalidates in-flight verified candidate");
        check(!fixture.client.hasSession(), "in-flight login cannot resurrect logged-out session");
        equal(86038, fixture.client.pollQr("synthetic-qr-key"), "canceled QR key cannot resume after logout");
        fixture.complete();
    }

    private static void cancelDuringVerification() throws Exception {
        Fixture fixture = new Fixture();
        fixture.oldAccount();
        String previous = fixture.client.getCookie();
        fixture.generate();
        fixture.poll(0, "").cookies("SESSDATA=" + SYNTHETIC_SESSION + "; Domain=.bilibili.com; Path=/");
        fixture.nav().onResponse = () -> fixture.client.cancelQr();
        fixture.client.beginQr();
        equal(86038, fixture.client.pollQr("synthetic-qr-key"), "closing QR invalidates in-flight candidate verification");
        equal(previous, fixture.client.getCookie(), "closing QR preserves the existing verified account");
        equal(86038, fixture.client.pollQr("synthetic-qr-key"), "canceled candidate cannot resume");
        fixture.complete();
    }

    /** Opt-in connectivity check only: never display, scan or persist the generated QR. */
    private static void liveQrSmoke() throws Exception {
        BiliClient client = new BiliClient(memoryPreferences(new LinkedHashMap<>()));
        JSONObject qr = client.beginQr();
        int status = client.pollQr(qr.getString("qrcode_key"));
        equal(86101, status, "unscanned live QR waits for scanning");
        System.out.println("BiliClient live QR smoke: host=" + new URL(qr.getString("url")).getHost() + ", status=" + status);
    }

    private static void playerQualityTransactions() throws Exception {
        LocalServer.get().clear();
        PlaybackFixture ugc = playback(false, "120", playbackDash());
        JSONObject result = new JSONObject(ugc.spider.playerContent("视频 · 4K", "play:BV1xx411c7mD:123@qn=120", new ArrayList<>()));
        check(readManifest(result).contains("id=\"video_120\""), "UGC manual choice reaches exact 4K manifest");
        ugc.complete(1);

        PlaybackFixture pgc = playback(true, "64", playbackDash());
        result = new JSONObject(pgc.spider.playerContent("正片 · 720P", "playep:123@qn=64", new ArrayList<>()));
        check(readManifest(result).contains("id=\"video_64\""), "PGC result.video_info unwraps into exact 720P manifest");
        pgc.complete(1);

        PlaybackFixture legacy = playback(false, "80", playbackDash());
        result = new JSONObject(legacy.spider.playerContent("视频", "play:BV1xx411c7mD:123", new ArrayList<>()));
        check(readManifest(result).contains("id=\"video_80\""), "Old playback IDs retain automatic 1080P preference");
        legacy.complete(1);

        PlaybackFixture downgraded = playback(false, "120", playbackDurl(64));
        result = new JSONObject(downgraded.spider.playerContent("视频 · 4K", "play:BV1xx411c7mD:123@qn=120", new ArrayList<>()));
        equal("", result.getString("url"), "Server-downgraded durl is never mislabeled and played as selected 4K");
        check(result.getString("msg").contains("4K"), "Downgrade failure identifies the unavailable requested quality");
        downgraded.complete(1);

        PlaybackFixture invalid = playback(false, "120", playbackDash());
        for (String suffix : new String[] {"", "0", "-1", "abc", "120@qn=64", "120&other=1", "10000"}) {
            result = new JSONObject(invalid.spider.playerContent("视频", "play:BV1xx411c7mD:123@qn=" + suffix, new ArrayList<>()));
            equal("", result.getString("url"), "Invalid quality suffix cannot produce playback");
            check(!result.getString("msg").isEmpty(), "Invalid quality suffix returns an actionable error");
        }
        invalid.complete(0);
        LocalServer.get().clear();
    }

    private static void detailQualityProbe() throws Exception {
        Method choices = BiliStudy.class.getDeclaredMethod("qualityChoices", JSONObject.class);
        choices.setAccessible(true);
        PlaybackFixture available = playback(false, "127", playbackDurl(64));
        JSONObject vod = new JSONObject().put("vod_content", "Original description")
                .put("vod_play_from", "视频").put("vod_play_url", "第一集$play:BV1xx411c7mD:123");
        JSONObject expanded = (JSONObject) choices.invoke(available.spider, vod);
        equal("自动$$$720P", expanded.getString("vod_play_from"), "Detail callback exposes only short quality names");
        check(expanded.getString("vod_play_url").contains("play:BV1xx411c7mD:123@qn=64"), "Detail callback binds exact quality to episode ID");
        check(expanded.getString("vod_content").startsWith("Original description"), "Quality choices preserve existing description");
        available.complete(1);

        PlaybackFixture failed = new PlaybackFixture("/x/player/wbi/playurl", playbackParams(false, "127"),
                new JSONObject().put("code", -104).put("message", "synthetic API failure"));
        vod = new JSONObject().put("vod_content", "Original description")
                .put("vod_play_from", "视频").put("vod_play_url", "第一集$play:BV1xx411c7mD:123");
        JSONObject preserved = (JSONObject) choices.invoke(failed.spider, vod);
        equal("自动", preserved.getString("vod_play_from"), "Failed quality probe still exposes only the automatic quality line");
        equal("第一集$play:BV1xx411c7mD:123", preserved.getString("vod_play_url"), "Failed quality probe preserves original episode IDs");
        check(preserved.getString("vod_content").startsWith("Original description"), "Failed quality probe preserves description");
        check(preserved.getString("vod_content").contains("重新进入详情页"), "Failed quality probe explains retry without hiding playback");
        failed.complete(1);
    }

    /** Exercise all six real PGC callback requests, without calling an external service. */
    private static void mediaRecommendationTransactions() throws Exception {
        List<FakeConnection> opened = java.util.Collections.synchronizedList(new ArrayList<>());
        BiliClient client = new BiliClient(memoryPreferences(new LinkedHashMap<>()), url -> {
            Map<String, String> query = new LinkedHashMap<>();
            for (String pair : url.getQuery().split("&")) {
                String[] fields = pair.split("=", 2);
                query.put(URLDecoder.decode(fields[0], "UTF-8"), URLDecoder.decode(fields[1], "UTF-8"));
            }
            if (!url.getPath().equals("/pgc/season/index/result") || !"1".equals(query.get("page")))
                throw new AssertionError("Recommendations must request the public PGC first pages");
            try {
                int type = Integer.parseInt(query.get("season_type"));
                JSONArray rows = new JSONArray().put(new JSONObject().put("season_id", type)
                        .put("title", "Type " + type).put("cover", "https://i0.hdslb.com/test.jpg"))
                        .put(new JSONObject().put("season_id", 999).put("title", "Shared season"));
                FakeConnection response = new FakeConnection(url.toString(), 200,
                        new JSONObject().put("code", 0).put("data", new JSONObject().put("list", rows)).toString());
                opened.add(response);
                return response;
            } catch (Exception error) { throw new IOException("Invalid synthetic PGC fixture", error); }
        });
        BiliStudy spider = new BiliStudy();
        Field field = BiliStudy.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(spider, client);
        JSONObject response = new JSONObject(spider.homeVideoContent());
        JSONArray rows = response.getJSONArray("list");
        equal(7, rows.length(), "All six PGC categories plus shared season are present without duplicates");
        int[] order = {2, 7, 3, 4, 5, 1};
        for (int i = 0; i < order.length; i++)
            equal("season:" + order[i], rows.getJSONObject(i).getString("vod_id"), "PGC recommendations interleave in category order");
        equal("season:999", rows.getJSONObject(6).getString("vod_id"), "Shared PGC item appears only once");
        equal(6, opened.size(), "One home refresh requests exactly six PGC types");
        for (FakeConnection connection : opened) check(connection.disconnected, "PGC recommendation releases its HTTP connection");
        equal(7, new JSONObject(spider.homeVideoContent()).getJSONArray("list").length(), "Cached home keeps all PGC results");
        equal(7, new JSONObject(spider.categoryContent("all", "1", false, null)).getInt("total"), "All tab uses the same media snapshot");
        equal(6, opened.size(), "Cached home and all pagination do not refetch PGC data");
    }

    /** Cloud IP restrictions are reported as blocked, never as a successful playback probe. */
    private static void reportLivePlaybackSmoke() throws Exception {
        try { livePlaybackSmoke(); }
        catch (IOException error) {
            boolean limited;
            if (error instanceof BiliClient.ApiException) {
                int code = ((BiliClient.ApiException) error).code;
                limited = code == -412 || code == -352 || code == -509;
            } else {
                limited = "Bilibili 暂时限制访问，请稍后重试（HTTP 412）".equals(error.getMessage())
                        || "Bilibili 暂时限制访问，请稍后重试（HTTP 429）".equals(error.getMessage());
            }
            if (!limited) throw error;
            System.out.println("::warning title=Bilibili live playback blocked::BiliClient live playback smoke: BLOCKED by upstream rate control; no live playback result. Offline quality and callback regressions remain required.");
        }
    }

    /** Opt-in anonymous API check; validates manifests without downloading any media. */
    private static void livePlaybackSmoke() throws Exception {
        BiliClient client = new BiliClient(memoryPreferences(new LinkedHashMap<>()));
        String bvid = "BV1xx411c7mD";
        JSONObject view = client.get("/x/web-interface/wbi/view", map("bvid", bvid));
        String cid = view.optString("cid", "");
        check(cid.matches("[1-9][0-9]*"), "Anonymous real video metadata returns a valid CID");
        JSONObject data = client.get("/x/player/wbi/playurl", map("bvid", bvid, "cid", cid,
                "qn", "127", "fnval", "4048", "fnver", "0", "fourk", "1"));
        List<Integer> qualities = PlaybackQuality.available(data);
        check(!qualities.isEmpty(), "Anonymous real playback exposes at least one usable quality");
        JSONObject dash = data.optJSONObject("dash");
        check(dash != null, "Anonymous real playback returns DASH tracks");
        for (int quality : qualities) {
            String manifest = Dash.createExact(dash, quality);
            check(manifest.contains("id=\"video_" + quality + "\""), "Real quality produces an exact manifest");
            check(manifest.contains("contentType=\"audio\""), "Real quality retains a usable audio representation");
        }
        check(!client.hasSession(), "Live playback probe remains anonymous");
        System.out.println("BiliClient live playback smoke: qualities=" + qualities);
    }

    private static PlaybackFixture playback(boolean pgc, String quality, JSONObject data) throws Exception {
        return new PlaybackFixture(pgc ? "/pgc/player/web/v2/playurl" : "/x/player/wbi/playurl",
                playbackParams(pgc, quality), new JSONObject().put("code", 0)
                .put(pgc ? "result" : "data", pgc ? new JSONObject().put("video_info", data) : data));
    }

    private static Map<String, String> playbackParams(boolean pgc, String quality) {
        Map<String, String> result = map("qn", quality, "fnval", "4048", "fnver", "0", "fourk", "1");
        if (pgc) result.put("ep_id", "123");
        else { result.put("bvid", "BV1xx411c7mD"); result.put("cid", "123"); }
        return result;
    }

    private static JSONObject playbackDurl(int quality) throws Exception {
        return new JSONObject().put("quality", quality).put("durl", new JSONArray().put(new JSONObject()
                .put("url", "https://test.bilivideo.com/synthetic-video.mp4")));
    }

    private static JSONObject playbackDash() throws Exception {
        return new JSONObject().put("quality", 80).put("dash", new JSONObject().put("duration", 30)
                .put("video", new JSONArray().put(playbackTrack(64, false)).put(playbackTrack(80, false))
                        .put(playbackTrack(120, false)))
                .put("audio", new JSONArray().put(playbackTrack(30280, true))));
    }

    private static JSONObject playbackTrack(int quality, boolean audio) throws Exception {
        return new JSONObject().put("id", quality).put("bandwidth", audio ? 192000 : 2000000)
                .put("codecs", audio ? "mp4a.40.2" : quality == 120 ? "hev1.1.6.L153.90" : "avc1.640028")
                .put("mimeType", audio ? "audio/mp4" : "video/mp4")
                .put("baseUrl", "https://test.bilivideo.com/synthetic-track.m4s")
                .put("SegmentBase", new JSONObject().put("Initialization", "0-733").put("indexRange", "734-2000"));
    }

    private static String readManifest(JSONObject player) throws Exception {
        equal(0, player.getInt("parse"), "Native playback skips remote parsing");
        URL url = new URL(player.getString("url"));
        equal("127.0.0.1", url.getHost(), "Callback returns device-local MPD URL");
        check(url.getPath().endsWith(".mpd"), "Callback keeps TVBox-compatible MPD URL");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally { connection.disconnect(); }
    }

    /** Real spider callback plus real BiliClient signing, with a synthetic HTTP transport. */
    private static final class PlaybackFixture {
        final BiliStudy spider = new BiliStudy();
        final FakeConnection response;
        int requests;

        PlaybackFixture(String path, Map<String, String> expected, JSONObject envelope) throws Exception {
            response = new FakeConnection("https://api.bilibili.com" + path, 200, envelope.toString());
            BiliClient client = new BiliClient(memoryPreferences(new LinkedHashMap<>()), url -> {
                requests++;
                equal("api.bilibili.com", url.getHost(), "Playback callback uses official API host");
                equal(path, url.getPath(), "Playback callback uses correct UGC/PGC endpoint");
                Map<String, String> query = new LinkedHashMap<>();
                for (String pair : url.getQuery().split("&")) {
                    String[] parts = pair.split("=", 2);
                    query.put(URLDecoder.decode(parts[0], "UTF-8"), URLDecoder.decode(parts[1], "UTF-8"));
                }
                for (Map.Entry<String, String> value : expected.entrySet())
                    equal(value.getValue(), query.get(value.getKey()), "Playback wire parameter " + value.getKey());
                boolean signed = path.contains("/wbi/");
                equal(expected.size() + (signed ? 2 : 0), query.size(), "Playback query contains only expected parameters and signature");
                if (signed) {
                    check(query.get("w_rid").matches("[0-9a-f]{32}"), "UGC callback carries a WBI signature");
                    check(Math.abs(System.currentTimeMillis() / 1000L - Long.parseLong(query.get("wts"))) <= 2,
                            "UGC callback carries current WBI timestamp");
                }
                return response;
            });
            Field mixin = BiliClient.class.getDeclaredField("mixinKey");
            mixin.setAccessible(true);
            mixin.set(client, "ea1db124af3c7062474693fa704f4ff8");
            Field expires = BiliClient.class.getDeclaredField("mixinExpires");
            expires.setAccessible(true);
            expires.setLong(client, Long.MAX_VALUE);
            Field dependency = BiliStudy.class.getDeclaredField("client");
            dependency.setAccessible(true);
            dependency.set(spider, client);
        }

        void complete(int count) {
            equal(count, requests, "Playback callback makes exactly the expected number of requests");
            if (count > 0) check(response.disconnected, "Playback callback releases API connection");
        }
    }

    /** All endpoints are fake; these transactions never leave the JVM. */
    private static final class Fixture {
        final Map<String, String> saved = new LinkedHashMap<>();
        final Deque<FakeConnection> pending = new ArrayDeque<>();
        final List<FakeConnection> opened = new ArrayList<>();
        final BiliClient client = new BiliClient(memoryPreferences(saved), url -> {
            FakeConnection response = pending.pollFirst();
            if (response == null) throw new AssertionError("unexpected network request to " + url.getHost() + url.getPath());
            equal(response.urlString, url.toExternalForm(), "HTTP transaction order and target");
            opened.add(response);
            return response;
        });

        FakeConnection add(String url, int code, String body) throws Exception {
            FakeConnection result = new FakeConnection(url, code, body);
            pending.add(result);
            return result;
        }

        FakeConnection generate() throws Exception {
            return generate("https://account.bilibili.com/h5/account-h5/auth/scan-web?navhide=1&callback=close&qrcode_key=synthetic-qr-key&from=");
        }

        FakeConnection generate(String scanUrl) throws Exception {
            return add(GENERATE, 200, new JSONObject().put("code", 0).put("data", new JSONObject()
                    .put("qrcode_key", "synthetic-qr-key")
                    .put("url", scanUrl)).toString());
        }

        FakeConnection poll(int code, String url) throws Exception {
            return add(POLL, 200, new JSONObject().put("code", 0).put("data", new JSONObject()
                    .put("code", code).put("url", url)).toString());
        }

        FakeConnection nav() throws Exception {
            return add(NAV, 200, "{\"code\":0,\"data\":{\"isLogin\":true,\"mid\":42,\"uname\":\"synthetic-user\"}}");
        }

        void oldAccount() throws Exception {
            JSONObject cookies = new JSONObject();
            for (Map.Entry<String, String> value : map("SESSDATA", "synthetic-old", "DedeUserID", "99",
                    "DedeUserID__ckMd5", "synthetic-old-checksum").entrySet())
                cookies.put(value.getKey(), new JSONObject().put("value", value.getValue()).put("expires", 0));
            saved.put("cookies", cookies.toString());
        }

        void complete() {
            check(pending.isEmpty(), "all expected fake transactions completed");
            for (FakeConnection response : opened) check(response.disconnected, "HTTP resources released after transaction");
        }
    }

    private static SharedPreferences memoryPreferences(Map<String, String> saved) {
        return (SharedPreferences) Proxy.newProxyInstance(BiliClientTest.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getString")) return saved.containsKey(args[0]) ? saved.get(args[0]) : args[1];
                    if (method.getName().equals("edit")) {
                        Map<String, String> staged = new LinkedHashMap<>();
                        return Proxy.newProxyInstance(BiliClientTest.class.getClassLoader(),
                                new Class<?>[] {SharedPreferences.Editor.class}, (editor, edit, values) -> {
                                    if (edit.getName().equals("putString")) { staged.put((String) values[0], (String) values[1]); return editor; }
                                    if (edit.getName().equals("remove")) { staged.put((String) values[0], null); return editor; }
                                    if (edit.getName().equals("commit")) {
                                        for (Map.Entry<String, String> value : staged.entrySet()) {
                                            if (value.getValue() == null) saved.remove(value.getKey()); else saved.put(value.getKey(), value.getValue());
                                        }
                                        return true;
                                    }
                                    throw new AssertionError("unexpected SharedPreferences.Editor method " + edit.getName());
                                });
                    }
                    throw new AssertionError("unexpected SharedPreferences method " + method.getName());
                });
    }

    private static final class FakeConnection extends HttpURLConnection {
        final String urlString;
        final String body;
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        int status;
        IOException responseFailure;
        IOException bodyFailure;
        Runnable onResponse;
        boolean bodyRead;
        boolean disconnected;

        FakeConnection(String url, int status, String body) throws Exception {
            super(new URL(url));
            this.urlString = url;
            this.status = status;
            this.body = body;
        }

        FakeConnection cookies(String... values) {
            headers.put("Set-Cookie", Arrays.asList(values));
            return this;
        }

        public int getResponseCode() throws IOException {
            if (onResponse != null) onResponse.run();
            if (responseFailure != null) throw responseFailure;
            return status;
        }

        public Map<String, List<String>> getHeaderFields() { return headers; }
        public String getHeaderField(String name) {
            for (Map.Entry<String, List<String>> header : headers.entrySet())
                if (header.getKey().equalsIgnoreCase(name)) return header.getValue().get(0);
            return null;
        }
        public InputStream getInputStream() throws IOException {
            bodyRead = true;
            if (bodyFailure != null) throw bodyFailure;
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }
        public void connect() { }
        public boolean usingProxy() { return false; }
        public void disconnect() { disconnected = true; }
    }

    private static Method method(String name, Class<?>... types) throws Exception {
        Method result = BiliClient.class.getDeclaredMethod(name, types);
        result.setAccessible(true);
        return result;
    }

    private static void rejectsIo(Method method, String address) throws Exception {
        try { method.invoke(null, address); throw new AssertionError("unapproved URL accepted"); }
        catch (InvocationTargetException expected) { check(expected.getCause() instanceof IOException, "unapproved URL blocked"); }
    }

    private static Map<String, String> map(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put(values[index], values[index + 1]);
        return result;
    }

    private static void equal(Object expected, Object actual, String message) {
        check(expected.equals(actual), message + " expected=" + expected + " actual=" + actual);
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
