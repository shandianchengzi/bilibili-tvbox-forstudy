package com.github.catvod.spider.bili;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/** Offline wire-contract/security regressions; no Android methods or live account required. */
public final class BiliClientTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        wbiPublishedVector();
        wbiUnicodeAndFreshTimestamp();
        cookieFreePlaybackHeaders();
        endpointHostBoundary();
        cookieHeaderValidation();
        legacyQrCookies();
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
                "https://bilibili.com/x", "file:///etc/passwd"}) {
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
