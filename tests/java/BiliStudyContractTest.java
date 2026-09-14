import com.github.catvod.spider.BiliStudy;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
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
        assertCompleteRecommendations(spider, categories.length(), 1);
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
        assertCompleteRecommendations(spider, names.length, 1000);

        set(spider, "mode", "media");
        JSONObject mediaHome = new JSONObject(spider.homeContent(true));
        check(mediaHome.getJSONArray("class").length() == 10, "Media exposes all, three personal categories and six PGC categories");
        assertNoAccountCategory(mediaHome);
        JSONArray mediaSnapshot = new JSONArray();
        for (int i = 0; i < 6; i++) mediaSnapshot.put(new JSONObject().put("vod_id", "season:" + (i + 1)).put("vod_name", "影视 " + i));
        set(spider, "mediaSnapshot", mediaSnapshot);
        set(spider, "mediaLoadedAt", System.currentTimeMillis());
        JSONArray mediaRows = new JSONObject(spider.homeVideoContent()).getJSONArray("list");
        check(mediaRows.length() == 6, "Media recommendations include all PGC snapshot groups");
        for (int i = 0; i < mediaRows.length(); i++) check(mediaRows.getJSONObject(i).getString("vod_id").startsWith("season:"), "Media recommendations cannot use a study or Zhou catalog");
        check(new JSONObject(spider.categoryContent("all", "1", false, null)).getInt("total") == 6, "Media all uses the same PGC snapshot");

        set(spider, "mode", "account");
        JSONObject accountHome = new JSONObject(spider.homeContent(true));
        check(accountHome.getJSONArray("class").length() == 0, "Account source needs no nested category");
        JSONArray accountRows = new JSONObject(spider.homeVideoContent()).getJSONArray("list");
        check(accountRows.length() == 3, "Account source shows login, status and logout");
        for (int i = 0; i < 3; i++) check(accountRows.getJSONObject(i).getString("vod_id").equals(new String[]{"auth:login", "auth:status", "auth:logout"}[i]), "Account actions retain their intended order");
        check(new JSONObject(spider.searchContent("周深", false)).getJSONArray("list").length() == 0, "Account source never participates in search");
        check(new JSONObject(spider.categoryContent("tag:knowledge", "1", false, null)).getJSONArray("list").length() == 0, "Account source does not expose a content category");
        System.out.println("BiliStudyContractTest: " + checks + " checks passed (standalone account, complete mode-local recommendations, stable all pagination)");
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
        }
    }

    private static void assertNoAccountCategory(JSONObject home) throws Exception {
        JSONArray rows = home.getJSONArray("class");
        for (int i = 0; i < rows.length(); i++) check(!rows.getJSONObject(i).getString("type_id").equals("account"), "Content categories cannot contain account");
    }

    private static void assertCompleteRecommendations(BiliStudy spider, int categories, int seed) throws Exception {
        JSONObject home = new JSONObject(spider.homeVideoContent());
        JSONArray rows = home.getJSONArray("list");
        int expected = categories * 3 + 24;
        check(rows.length() == expected && expected > 20, "Recommendations must return every unique indexed video, including beyond page one");
        check(home.getInt("total") == expected && home.getInt("limit") == expected, "Home metadata cannot imply a 20-row truncation");
        Set<String> unique = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            String id = rows.getJSONObject(i).getString("vod_id");
            check(id.startsWith("video:BV"), "Recommendations consist of playable videos, not login/notice cards");
            check(unique.add(id), "Repeated BV across categories is deduplicated");
        }
        for (int i = 0; i < categories; i++) check(rows.getJSONObject(i).getString("vod_id").equals("video:" + bv(seed + i * 100)), "Round robin covers every category before repeating one");
        int seen = 0;
        int count = (expected + 19) / 20;
        for (int page = 1; page <= count; page++) {
            JSONObject part = new JSONObject(spider.categoryContent("all", String.valueOf(page), false, null));
            check(part.getInt("pagecount") == count && part.getInt("total") == expected, "All pagination reports the complete stable snapshot");
            JSONArray items = part.getJSONArray("list");
            check(items.length() <= 20, "Each all page contains at most 20 videos");
            for (int j = 0; j < items.length(); j++) check(items.getJSONObject(j).getString("vod_id").equals(rows.getJSONObject(seen++).getString("vod_id")), "All pagination neither skips nor reorders recommendations");
        }
        check(seen == expected, "All pages cover every recommendation");
        check(new JSONObject(spider.categoryContent("all", String.valueOf(count + 1), false, null)).getJSONArray("list").length() == 0, "Out-of-range all page is empty");
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
    private static JSONObject video(int id) throws Exception { return new JSONObject().put("bvid", bv(id)).put("title", "Video " + id).put("author", "Author").put("duration", "01:23").put("pic", "https://example.com/cover.jpg"); }
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
