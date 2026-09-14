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

/** Executes the actual TVBox home callback against the shipped interest configuration. */
public final class BiliStudyContractTest {
    public static void main(String[] args) throws Exception {
        BiliStudy spider = new BiliStudy();
        JSONObject interests = new JSONObject(new String(Files.readAllBytes(Paths.get("config/interests.json")), StandardCharsets.UTF_8));
        set(spider, "mode", "study");
        set(spider, "interests", interests);
        set(spider, "catalog", new JSONObject());
        set(spider, "loadedAt", System.currentTimeMillis());
        JSONObject home = new JSONObject(spider.homeContent(true));
        JSONArray categories = interests.getJSONArray("categories");
        if (home.getJSONArray("class").length() != categories.length() + 1)
            throw new AssertionError("Every configured category plus account must be visible");
        boolean audiobooks = false;
        for (int i = 0; i < categories.length(); i++) {
            JSONObject category = categories.getJSONObject(i);
            audiobooks |= category.getString("id").equals("audiobooks");
            JSONArray filters = home.getJSONObject("filters").getJSONArray("tag:" + category.getString("id"));
            assertValues(filters, "order", "totalrank", "click", "pubdate", "dm", "stow");
            assertValues(filters, "duration", "0", "4", "3", "2", "1");
        }
        if (!audiobooks) throw new AssertionError("Requested audiobook category is missing");
        JSONObject zhou = new JSONObject(new String(Files.readAllBytes(Paths.get("config/zhou_shen.json")), StandardCharsets.UTF_8));
        set(spider, "mode", "zhou_shen");
        set(spider, "interests", zhou);
        set(spider, "loadedAt", System.currentTimeMillis());
        JSONObject zhouHome = new JSONObject(spider.homeContent(true));
        String[] expectedNames = {"综艺", "演唱会", "歌曲", "采访", "剪辑", "搞笑", "舞台", "卡布"};
        JSONArray visible = zhouHome.getJSONArray("class");
        if (visible.length() != expectedNames.length + 1) throw new AssertionError("Zhou Shen must show eight categories plus account");
        for (int i = 0; i < expectedNames.length; i++) {
            JSONObject row = visible.getJSONObject(i + 1);
            if (!expectedNames[i].equals(row.getString("type_name"))) throw new AssertionError("Zhou Shen categories must match requested order");
            JSONArray filters = zhouHome.getJSONObject("filters").getJSONArray(row.getString("type_id"));
            assertValues(filters, "order", "totalrank", "click", "pubdate", "dm", "stow");
            assertValues(filters, "duration", "0", "4", "3", "2", "1");
        }
        System.out.println("BiliStudyContractTest: study and Zhou Shen categories expose all requested filters");
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = BiliStudy.class.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static void assertValues(JSONArray filters, String key, String... expected) throws Exception {
        for (int i = 0; i < filters.length(); i++) {
            JSONObject filter = filters.getJSONObject(i);
            if (!key.equals(filter.getString("key"))) continue;
            JSONArray values = filter.getJSONArray("value");
            Set<String> actual = new HashSet<>();
            for (int j = 0; j < values.length(); j++) actual.add(values.getJSONObject(j).getString("v"));
            if (!actual.equals(new HashSet<>(Arrays.asList(expected)))) throw new AssertionError("Incorrect filter " + key);
            if (!values.getJSONObject(0).getString("v").equals(expected[0])) throw new AssertionError("Incorrect default " + key);
            return;
        }
        throw new AssertionError("Missing filter " + key);
    }
}
