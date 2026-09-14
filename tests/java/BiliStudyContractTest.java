import com.github.catvod.spider.BiliStudy;
import com.github.catvod.spider.bili.PgcFilters;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** Runs the actual TVBox navigation/home/page callbacks without Android UI or network. */
public final class BiliStudyContractTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        JSONObject study = config("config/interests.json");
        JSONObject zhou = config("config/zhou_shen.json");
        BiliStudy spider = new BiliStudy();
        useCatalog(spider, "study", study, catalog(study, 1));
        JSONObject studyHome = new JSONObject(spider.homeContent(true));
        assertCategories(studyHome, study);
        boolean audiobooks = false;
        JSONArray categories = study.getJSONArray("categories");
        for (int i = 0; i < categories.length(); i++) audiobooks |= "audiobooks".equals(categories.getJSONObject(i).getString("id"));
        check(audiobooks, "Requested audiobook category is missing");
        assertCompleteAll(spider, categories.length(), 1);
        assertFilteredAll(study, "study", zhou.getJSONArray("categories").getJSONObject(0).getString("id"));
        for (String personal : Arrays.asList("account", "dynamic", "favorites", "history")) {
            JSONArray rows = new JSONObject(spider.categoryContent(personal, "1", false, null)).getJSONArray("list");
            check(rows.length() == 1 && rows.getJSONObject(0).getString("vod_id").startsWith("notice:"), "Content source should point to the standalone account source");
        }
        JSONObject oldLogin = new JSONObject(spider.detailContent(Arrays.asList("auth:login"))).getJSONArray("list").getJSONObject(0);
        check(oldLogin.getString("vod_id").startsWith("notice:"), "Old login details must not open QR inside content sources");

        // Mixed cached input proves that only category ids belonging to this module participate.
        JSONObject zhouCatalog = catalog(zhou, 1000);
        JSONArray mixed = zhouCatalog.getJSONArray("categories");
        JSONArray studyRows = catalog(study, 1).getJSONArray("categories");
        for (int i = 0; i < studyRows.length(); i++) mixed.put(studyRows.getJSONObject(i));
        useCatalog(spider, "zhou_shen", zhou, zhouCatalog);
        JSONObject zhouHome = new JSONObject(spider.homeContent(true));
        assertCategories(zhouHome, zhou);
        String[] names = {"综艺", "演唱会", "歌曲", "采访", "剪辑", "搞笑", "舞台", "卡布"};
        for (int i = 0; i < names.length; i++)
            check(names[i].equals(zhouHome.getJSONArray("class").getJSONObject(i + 1).getString("type_name")), "Zhou Shen categories must match requested order");
        assertCompleteAll(spider, names.length, 1000);
        assertFilteredAll(zhou, "zhou_shen", "knowledge");

        set(spider, "mode", "media");
        JSONObject mediaHome = new JSONObject(spider.homeContent(true));
        check(mediaHome.getJSONArray("class").length() == 10, "Media exposes all, three personal categories and six PGC categories");
        assertNoAccountCategory(mediaHome);
        assertPgcFilters(mediaHome);
        JSONArray mediaSnapshot = new JSONArray();
        for (int i = 0; i < 6; i++) mediaSnapshot.put(new JSONObject().put("vod_id", "season:" + (i + 1)).put("vod_name", "影视 " + i));
        set(spider, "mediaSnapshot", mediaSnapshot);
        set(spider, "mediaLoadedAt", System.currentTimeMillis());
        set(spider, "mediaFilterKey", PgcFilters.key(new HashMap<String, String>()));
        assertEmptyRecommendations(spider);
        JSONArray mediaRows = new JSONObject(spider.categoryContent("all", "1", false, null)).getJSONArray("list");
        check(mediaRows.length() == 6, "Media all includes all PGC snapshot groups");
        for (int i = 0; i < mediaRows.length(); i++) check(mediaRows.getJSONObject(i).getString("vod_id").startsWith("season:"), "Media all cannot use a study or Zhou catalog");
        check(new JSONObject(spider.categoryContent("all", "1", false, null)).getInt("total") == 6, "Media all uses the same PGC snapshot");

        set(spider, "mode", "account");
        JSONObject accountHome = new JSONObject(spider.homeContent(true));
        check(accountHome.getJSONArray("class").length() == 0, "Account source needs no nested category");
        JSONArray accountRows = new JSONObject(spider.homeVideoContent()).getJSONArray("list");
        check(accountRows.length() == 3, "Account source shows login, status and logout");
        for (int i = 0; i < 3; i++) check(accountRows.getJSONObject(i).getString("vod_id").equals(new String[]{"auth:login", "auth:status", "auth:logout"}[i]), "Account actions retain their intended order");
        check(new JSONObject(spider.searchContent("周深", false)).getJSONArray("list").length() == 0, "Account source never participates in search");
        check(new JSONObject(spider.categoryContent("tag:knowledge", "1", false, null)).getJSONArray("list").length() == 0, "Account source does not expose a content category");
        System.out.println("BiliStudyContractTest: " + checks + " checks passed (standalone account, no duplicate recommendations, filtered mode-local all pagination)");
    }

    private static void assertCategories(JSONObject home, JSONObject config) throws Exception {
        JSONArray configured = config.getJSONArray("categories");
        JSONArray visible = home.getJSONArray("class");
        check(visible.length() == configured.length() + 1, "Every configured category plus all must be visible");
        check("all".equals(visible.getJSONObject(0).getString("type_id")), "All should be first");
        assertNoAccountCategory(home);
        for (int i = 0; i < configured.length(); i++) {
            String id = "tag:" + configured.getJSONObject(i).getString("id");
            check(id.equals(visible.getJSONObject(i + 1).getString("type_id")), "Configured category order is preserved");
            JSONArray filters = home.getJSONObject("filters").getJSONArray(id);
            assertValues(filters, "order", "totalrank", "click", "pubdate", "dm", "stow");
            assertValues(filters, "duration", "0", "4", "3", "2", "1");
            assertValues(filters, "plays", "all", "10k_100k", "100k_plus", "1k_10k", "lt_1k");
        }
        JSONArray filters = home.getJSONObject("filters").getJSONArray("all");
        String[] ids = new String[configured.length() + 1];
        ids[0] = "all";
        for (int i = 0; i < configured.length(); i++) ids[i + 1] = configured.getJSONObject(i).getString("id");
        assertValues(filters, "category", ids);
        assertValues(filters, "order", "totalrank", "click", "pubdate", "dm", "stow");
        assertValues(filters, "duration", "0", "4", "3", "2", "1");
        assertValues(filters, "plays", "all", "10k_100k", "100k_plus", "1k_10k", "lt_1k");
    }

    private static void assertPgcFilters(JSONObject home) throws Exception {
        JSONObject filters = home.getJSONObject("filters");
        JSONArray all = filters.getJSONArray("all");
        assertValues(all, "type", "all", "2", "7", "3", "4", "5", "1");
        assertValues(all, "order", "2", "0", "4");
        assertValues(all, "season_status", "-1", "1", "4,6");
        for (String type : Arrays.asList("2", "7", "3", "4", "5", "1")) {
            JSONArray options = filters.getJSONArray("pgc:" + type);
            check(options.length() == 2, "Each PGC category exposes its supported sort and payment filters");
            assertValues(options, "order", "2", "0", "4");
            assertValues(options, "season_status", "-1", "1", "4,6");
        }
        assertValues(filters.getJSONArray("favorites"), "plays", "all", "10k_100k", "100k_plus", "1k_10k", "lt_1k");
    }

    /** Filter a complete local directory before pagination; the first 25 entries cannot match. */
    private static void assertFilteredAll(JSONObject config, String mode, String foreignCategory) throws Exception {
        JSONArray configured = config.getJSONArray("categories");
        String first = configured.getJSONObject(0).getString("id");
        String second = configured.getJSONObject(1).getString("id");
        JSONArray entries = new JSONArray();
        for (int i = 0; i < 45; i++) entries.put(video(5000 + i)
                .put("play", i < 25 ? 500 : 10000 + i).put("duration", i < 30 ? "05:00" : "61:00")
                .put("pubdate", 1700000000L + i).put("video_review", i * 7).put("favorites", 100 - i));
        JSONObject unknown = video(6000).put("pubdate", 1).put("video_review", 0).put("favorites", 0);
        unknown.remove("play");
        JSONArray groups = new JSONArray().put(new JSONObject().put("id", first).put("items", entries))
                .put(new JSONObject().put("id", second).put("items", new JSONArray().put(unknown)))
                .put(new JSONObject().put("id", foreignCategory).put("items", new JSONArray().put(video(900000))));
        BiliStudy spider = new BiliStudy();
        useCatalog(spider, mode, config, new JSONObject().put("categories", groups));
        HashMap<String, String> selected = new HashMap<>();
        selected.put("plays", "10k_100k"); selected.put("order", "click");
        JSONObject result = new JSONObject(spider.categoryContent("all", "1", true, selected));
        check(result.getInt("total") == 20 && result.getInt("pagecount") == 1,
                "Play-count filtering sees matching videos beyond the unfiltered first page");
        JSONArray rows = result.getJSONArray("list");
        for (int i = 0; i < rows.length(); i++)
            check(rows.getJSONObject(i).getString("vod_id").equals("video:" + bv(5044 - i)),
                    "Most-played filtering sorts all matching rows before pagination");
        selected.put("duration", "4");
        result = new JSONObject(spider.categoryContent("all", "1", true, selected));
        check(result.getInt("total") == 15 && result.getJSONArray("list").length() == 15,
                "All combines duration with play-count filtering");
        selected.clear(); selected.put("category", first); selected.put("plays", "lt_1k");
        result = new JSONObject(spider.categoryContent("all", "2", true, selected));
        check(result.getInt("total") == 25 && result.getInt("pagecount") == 2
                        && result.getJSONArray("list").length() == 5,
                "Filtered all pagination uses the matching total, not the original total");
        for (int i = 0; i < 5; i++)
            check(result.getJSONArray("list").getJSONObject(i).getString("vod_id").equals("video:" + bv(5020 + i)),
                    "Second filtered page neither skips nor duplicates videos");
        selected.clear(); selected.put("category", second);
        result = new JSONObject(spider.categoryContent("all", "1", true, selected));
        check(result.getInt("total") == 1
                        && result.getJSONArray("list").getJSONObject(0).getString("vod_id").equals("video:" + bv(6000)),
                "The all category selector restricts rows to its chosen local category");
        selected.put("plays", "lt_1k");
        check(new JSONObject(spider.categoryContent("all", "1", true, selected)).getJSONArray("list").length() == 0,
                "Missing play counts cannot be classified as fewer than 1000 views");
        selected.clear(); selected.put("category", foreignCategory);
        JSONArray foreign = new JSONObject(spider.categoryContent("all", "1", true, selected)).getJSONArray("list");
        for (int i = 0; i < foreign.length(); i++)
            check(!foreign.getJSONObject(i).getString("vod_id").equals("video:" + bv(900000)),
                    "A foreign category cannot import another module's cached rows");
        for (String sort : Arrays.asList("pubdate", "dm", "stow")) {
            selected.clear(); selected.put("order", sort);
            result = new JSONObject(spider.categoryContent("all", "1", true, selected));
            check(result.getInt("total") == 46, "Sorting does not omit unknown-count videos or import foreign categories");
            check(result.getJSONArray("list").getJSONObject(0).getString("vod_id")
                            .equals("video:" + bv("stow".equals(sort) ? 5000 : 5044)),
                    "All applies its selected public-metadata ordering: " + sort);
        }
    }

    private static void assertNoAccountCategory(JSONObject home) throws Exception {
        JSONArray rows = home.getJSONArray("class");
        for (int i = 0; i < rows.length(); i++) check(!rows.getJSONObject(i).getString("type_id").equals("account"), "Content categories cannot contain account");
    }

    private static void assertEmptyRecommendations(BiliStudy spider) throws Exception {
        check(new JSONObject(spider.homeContent(true)).getJSONArray("list").length() == 0,
                "Content home callback cannot create a duplicate recommendation tab");
        check(new JSONObject(spider.homeVideoContent()).getJSONArray("list").length() == 0,
                "Content home-video callback cannot create a duplicate recommendation tab");
    }

    private static void assertCompleteAll(BiliStudy spider, int categories, int seed) throws Exception {
        assertEmptyRecommendations(spider);
        int expected = categories * 3 + 24;
        check(expected > 20, "Fixture must cover multiple all pages");
        JSONArray rows = new JSONArray();
        Set<String> unique = new HashSet<>();
        int count = (expected + 19) / 20;
        for (int page = 1; page <= count; page++) {
            JSONObject part = new JSONObject(spider.categoryContent("all", String.valueOf(page), false, null));
            check(part.getInt("pagecount") == count && part.getInt("total") == expected,
                    "All pagination reports the complete stable snapshot");
            JSONArray items = part.getJSONArray("list");
            check(items.length() <= 20, "Each all page contains at most 20 videos");
            for (int j = 0; j < items.length(); j++) {
                JSONObject item = items.getJSONObject(j);
                String id = item.getString("vod_id");
                check(id.startsWith("video:BV"), "All contains playable videos, not login/notice cards");
                check(unique.add(id), "Repeated BV across categories and pages is deduplicated");
                rows.put(item);
            }
        }
        check(rows.length() == expected, "All pages include every unique indexed video");
        for (int i = 0; i < categories; i++) {
            check(rows.getJSONObject(i).getString("vod_id").equals("video:" + bv(seed + i * 100)),
                    "Default ordering covers every category before repeating one");
            for (int j = 0; j < (i == 0 ? 27 : 3); j++)
                check(unique.contains("video:" + bv(seed + i * 100 + j)), "All must not omit an indexed video");
        }
        JSONArray repeated = new JSONObject(spider.categoryContent("all", "1", false, null)).getJSONArray("list");
        for (int i = 0; i < repeated.length(); i++)
            check(repeated.getJSONObject(i).getString("vod_id").equals(rows.getJSONObject(i).getString("vod_id")),
                    "Reloading an unchanged all page keeps ordering stable");
        check(new JSONObject(spider.categoryContent("all", String.valueOf(count + 1), false, null))
                .getJSONArray("list").length() == 0, "Out-of-range all page is empty");
    }

    private static JSONObject catalog(JSONObject config, int seed) throws Exception {
        JSONArray categories = config.getJSONArray("categories"), cached = new JSONArray();
        for (int i = 0; i < categories.length(); i++) {
            JSONArray items = new JSONArray();
            int size = i == 0 ? 27 : 3;
            for (int j = 0; j < size; j++) items.put(video(seed + i * 100 + j));
            if (i > 0) items.put(video(seed));
            items.put(new JSONObject().put("bvid", "invalid").put("title", "Invalid BV"));
            cached.put(new JSONObject().put("id", categories.getJSONObject(i).getString("id")).put("items", items));
        }
        cached.put(new JSONObject().put("id", "unrelated_category").put("items", new JSONArray().put(video(900000))));
        return new JSONObject().put("categories", cached);
    }
    private static JSONObject video(int id) throws Exception { return new JSONObject().put("bvid", bv(id)).put("title", "Video " + id).put("author", "Author").put("duration", "01:23").put("pic", "https://example.com/cover.jpg").put("play", id * 1000L).put("pubdate", 1700000000L + id).put("video_review", id * 10L).put("favorites", id * 2L); }
    private static String bv(int id) { return String.format(java.util.Locale.ROOT, "BV%010d", id); }
    private static JSONObject config(String path) throws Exception { return new JSONObject(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)); }
    private static void useCatalog(BiliStudy spider, String mode, JSONObject interests, JSONObject catalog) throws Exception {
        set(spider, "mode", mode); set(spider, "interests", interests); set(spider, "catalog", catalog); set(spider, "loadedAt", System.currentTimeMillis());
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = BiliStudy.class.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void assertValues(JSONArray filters, String key, String... expected) throws Exception {
        for (int i = 0; i < filters.length(); i++) {
            JSONObject filter = filters.getJSONObject(i);
            if (!key.equals(filter.getString("key"))) continue;
            JSONArray values = filter.getJSONArray("value");
            Set<String> actual = new HashSet<>();
            for (int j = 0; j < values.length(); j++) actual.add(values.getJSONObject(j).getString("v"));
            check(actual.equals(new HashSet<>(Arrays.asList(expected))), "Incorrect filter " + key);
            check(values.getJSONObject(0).getString("v").equals(expected[0]), "Incorrect default " + key);
            return;
        }
        throw new AssertionError("Missing filter " + key);
    }
}
