package com.github.catvod.spider.bili;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Verify bounded sparse-result scanning without networking or Android. */
public final class FilteredPagesTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        sparseMatchesAndFixedWindows();
        upstreamEndAndUnknownPagecount();
        allEmptyWindow();
        errorsPropagate();
        noScanAndDeduplication();
        pageOverflow();
        System.out.println("FilteredPagesTest: " + assertions + " assertions passed");
    }

    private static void sparseMatchesAndFixedWindows() throws Exception {
        final List<Integer> calls = new ArrayList<>();
        FilteredPages.PageLoader loader = new FilteredPages.PageLoader() {
            @Override public JSONObject load(int p) throws Exception {
                calls.add(p);
                return response(p, p + 1, p == 1 || p == 2 ? new String[0] : new String[] {"video:" + p});
            }
        };
        JSONObject first = FilteredPages.load(1, true, loader);
        equal(Arrays.asList(1, 2, 3), calls, "scan beyond two empty upstream pages");
        equal("video:3", ids(first), "third upstream page supplies a real match");
        equal(1, first.getInt("page"), "logical page remains one");
        equal(2, first.getInt("pagecount"), "continue only with upstream evidence");
        equal(60, first.getInt("limit"), "three upstream pages per logical page");
        equal(120, first.getInt("total"), "estimated total supports next logical page");
        calls.clear();
        JSONObject second = FilteredPages.load(2, true, loader);
        equal(Arrays.asList(4, 5, 6), calls, "next logical page never repeats previous upstream pages");
        equal("video:4,video:5,video:6", ids(second), "results retain upstream order");
        equal(2, second.getInt("page"), "second logical page is stable");
        equal(3, second.getInt("pagecount"), "next logical page is three");
    }

    private static void upstreamEndAndUnknownPagecount() throws Exception {
        final List<Integer> calls = new ArrayList<>();
        JSONObject result = FilteredPages.load(1, true, new FilteredPages.PageLoader() {
            @Override public JSONObject load(int p) throws Exception {
                calls.add(p);
                return response(p, 2, "video:" + p);
            }
        });
        equal(Arrays.asList(1, 2), calls, "stop at actual upstream end before third request");
        equal("video:1,video:2", ids(result), "short final window retains available matches");
        equal(1, result.getInt("pagecount"), "no fabricated continuation after final upstream page");
        equal(2, result.getInt("total"), "first final window uses actual matches");
        for (final Object value : new Object[] {null, JSONObject.NULL, "unknown", "2.5", -1}) {
            calls.clear();
            result = FilteredPages.load(1, true, new FilteredPages.PageLoader() {
                @Override public JSONObject load(int p) throws Exception {
                    calls.add(p);
                    return response(p, 9, "video:a").put("pagecount", value);
                }
            });
            equal(Arrays.asList(1), calls, "unknown page count cannot imply more pages");
            equal(1, result.getInt("pagecount"), "unknown page count ends current window");
        }
    }

    private static void allEmptyWindow() throws Exception {
        final List<Integer> calls = new ArrayList<>();
        JSONObject result = FilteredPages.load(2, true, new FilteredPages.PageLoader() {
            @Override public JSONObject load(int p) throws Exception {
                calls.add(p);
                return response(p, 999);
            }
        });
        equal(Arrays.asList(4, 5, 6), calls, "empty results have a hard three-request budget");
        equal(0, result.getJSONArray("list").length(), "do not inject a synthetic playable card");
        equal(2, result.getInt("pagecount"), "empty window cannot cause endless continuation");
        equal("本次检查的搜索结果中没有符合播放量的视频，请调整筛选", result.getString("msg"),
                "empty message describes this bounded window only");
    }

    private static void errorsPropagate() throws Exception {
        final Exception expected = new Exception("upstream failed");
        try {
            FilteredPages.load(1, true, new FilteredPages.PageLoader() {
                @Override public JSONObject load(int p) throws Exception {
                    if (p == 2) throw expected;
                    return response(p, 5, "video:a");
                }
            });
            fail("failed continuation must not silently return partial success");
        } catch (Exception actual) { check(actual == expected, "preserve original loader error"); }
        for (final JSONObject invalid : new JSONObject[] {null, new JSONObject(), new JSONObject().put("list", "bad")}) {
            try {
                FilteredPages.load(1, true, new FilteredPages.PageLoader() {
                    @Override public JSONObject load(int p) { return invalid; }
                });
                fail("missing list must not become an empty filtered result");
            } catch (IllegalStateException expectedFailure) { check(true, "malformed list rejected"); }
        }
    }

    private static void noScanAndDeduplication() throws Exception {
        final List<Integer> calls = new ArrayList<>();
        JSONObject result = FilteredPages.load(7, false, new FilteredPages.PageLoader() {
            @Override public JSONObject load(int p) throws Exception {
                calls.add(p);
                return response(p, p + 1, "video:a");
            }
        });
        equal(Arrays.asList(7), calls, "inactive filtering uses exactly one original upstream page");
        equal(20, result.getInt("limit"), "inactive filtering keeps ordinary page size");
        equal(8, result.getInt("pagecount"), "ordinary next page remains available");
        final List<JSONObject> responses = new ArrayList<>();
        result = FilteredPages.load(1, true, new FilteredPages.PageLoader() {
            @Override public JSONObject load(int p) throws Exception {
                JSONObject response = response(p, 3, "video:shared", "video:" + p);
                responses.add(response);
                return response;
            }
        });
        equal("video:shared,video:1,video:2,video:3", ids(result), "deduplicate vod ids within one window, preserving first occurrence");
        for (JSONObject response : responses) equal(2, response.getJSONArray("list").length(), "upstream arrays are not mutated");
        result = FilteredPages.load(1, true, new FilteredPages.PageLoader() {
            @Override public JSONObject load(int p) throws Exception {
                String[] ids = new String[30];
                for (int i = 0; i < ids.length; i++) ids[i] = "video:" + p + ":" + i;
                return response(p, 3, ids);
            }
        });
        equal(60, result.getJSONArray("list").length(), "hard output cap even for oversized upstream pages");
    }

    private static void pageOverflow() throws Exception {
        final List<Integer> calls = new ArrayList<>();
        FilteredPages.PageLoader loader = new FilteredPages.PageLoader() {
            @Override public JSONObject load(int p) throws Exception {
                calls.add(p);
                return response(p, 1, "video:a").put("pagecount", Long.MAX_VALUE);
            }
        };
        JSONObject result = FilteredPages.load(Integer.MIN_VALUE, true, loader);
        equal(Arrays.asList(1, 2, 3), calls, "negative logical page safely normalizes to one");
        equal(1, result.getInt("page"), "normalized page metadata");
        calls.clear();
        result = FilteredPages.load(Integer.MAX_VALUE, false, loader);
        equal(Arrays.asList(Integer.MAX_VALUE), calls, "largest direct page stays representable");
        equal(Integer.MAX_VALUE, result.getInt("pagecount"), "next-page arithmetic never wraps negative");
        equal(Integer.MAX_VALUE, result.getInt("total"), "estimated total saturates at client integer range");
        calls.clear();
        int lastWindow = (Integer.MAX_VALUE - 1) / 3 + 1;
        result = FilteredPages.load(lastWindow, true, loader);
        equal(Arrays.asList(Integer.MAX_VALUE), calls, "last representable scan window stops before overflowing");
        equal(lastWindow, result.getInt("pagecount"), "cannot continue to an unrepresentable window");
        calls.clear();
        try {
            FilteredPages.load(lastWindow + 1, true, loader);
            fail("out-of-range mapping must be rejected");
        } catch (IllegalArgumentException expected) { equal(0, calls.size(), "overflow rejected before any request"); }
    }

    private static JSONObject response(int page, int pagecount, String... ids) throws Exception {
        JSONArray rows = new JSONArray();
        for (String id : ids) rows.put(new JSONObject().put("vod_id", id).put("vod_name", id));
        return new JSONObject().put("list", rows).put("page", page).put("pagecount", pagecount)
                .put("limit", 20).put("total", (long) pagecount * 20);
    }

    private static String ids(JSONObject response) throws Exception {
        JSONArray rows = response.getJSONArray("list");
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < rows.length(); i++) {
            if (i > 0) ids.append(',');
            ids.append(rows.getJSONObject(i).getString("vod_id"));
        }
        return ids.toString();
    }

    private static void equal(Object expected, Object actual, String message) {
        check(expected.equals(actual), message + " expected=" + expected + " actual=" + actual);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void fail(String message) { throw new AssertionError(message); }
}
