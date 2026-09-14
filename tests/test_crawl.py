"""Deterministic tests for API signatures and public-cache failure behavior."""

import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location("crawl", Path(__file__).parents[1] / "scripts" / "crawl.py")
crawl = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(crawl)

NOW = "2026-09-14T12:00:00Z"
BEFORE = "2026-09-13T12:00:00Z"
ITEM = {
    "bvid": "BV1xx411c7mD", "title": "<em class=keyword>QEMU</em> &amp; 固件仿真",
    "pic": "//i0.hdslb.com/bfs/archive/public.jpg?token=discard#fragment",
    "author": "UP 主", "duration": "12:3", "cookie": "must-not-leak",
    "playurl": "https://video.example/private?token=private",
}
CONFIG = {"version": 1, "categories": [{
    "id": "firmware", "name": "固件仿真", "queries": ["QEMU"], "description": "公开元数据",
}]}


def previous_catalog():
    return {"schema": 1, "generated_at": BEFORE, "categories": [{
        "id": "firmware", "items": [ITEM], "updated_at": BEFORE,
        "cookie": "cached-private-secret",
    }]}


class SigningTests(unittest.TestCase):
    def test_fixed_wbi_vector(self):
        result = crawl.sign_wbi(
            {"foo": "114", "bar": "514", "zab": 1919810},
            "7cd084941338484aae1ad9425b84077c",
            "4932caff0ff746eab6f01bf08b70ac45", 1702204169,
        )
        self.assertEqual(result, {
            "bar": "514", "zab": "1919810", "foo": "114", "wts": "1702204169",
            "w_rid": "8f6f2b5b3d485fe1886cec6a0be8c5d4",
        })

    def test_signature_removes_forbidden_characters_and_ignores_supplied_signature(self):
        keys = ("7cd084941338484aae1ad9425b84077c", "4932caff0ff746eab6f01bf08b70ac45")
        plain = crawl.sign_wbi({"keyword": "量子计算"}, *keys, timestamp=10)
        dirty = crawl.sign_wbi({"keyword": "量!子'计(算)*", "wts": 999, "w_rid": "old"}, *keys, timestamp=10)
        self.assertEqual(plain, dirty)

    def test_guest_nav_code_is_accepted_for_public_keys(self):
        client = crawl.PublicClient()
        calls = []
        def fake_get(path):
            calls.append(path)
            return {"code": -101, "data": {"isLogin": False, "wbi_img": {
                "img_url": "https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png",
                "sub_url": "https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png",
            }}}
        client._get_json = fake_get
        self.assertEqual(client.public_keys()[0], "7cd084941338484aae1ad9425b84077c")
        client.public_keys()
        self.assertEqual(len(calls), 1)


class CatalogTests(unittest.TestCase):
    def test_normalizes_results_and_whitelists_public_fields(self):
        result = crawl.build_catalog(CONFIG, lambda _: [ITEM, ITEM], now=NOW)
        item = result["categories"][0]["items"][0]
        self.assertEqual(len(result["categories"][0]["items"]), 1)
        self.assertEqual(item["title"], "QEMU & 固件仿真")
        self.assertEqual(item["pic"], "https://i0.hdslb.com/bfs/archive/public.jpg")
        self.assertEqual(item["duration"], "12:03")
        self.assertEqual(set(item), {"bvid", "title", "pic", "author", "duration"})
        self.assertEqual(result["status"]["state"], "ok")

    def test_failed_query_preserves_sanitized_last_good_and_timestamp(self):
        def fail(_):
            raise crawl.CrawlError("Bilibili HTTP 412")
        result = crawl.build_catalog(CONFIG, fail, previous_catalog(), now=NOW)
        category = result["categories"][0]
        self.assertEqual(category["status"], "stale")
        self.assertEqual(category["updated_at"], BEFORE)
        self.assertEqual(category["items"][0]["bvid"], ITEM["bvid"])
        self.assertNotIn("cookie", str(result))
        self.assertNotIn("token", str(result))
        self.assertEqual(result["status"]["stale_categories"], 1)

    def test_initial_failure_exposes_empty_category_without_invented_results(self):
        def fail(_):
            raise RuntimeError("https://api.example?secret=do-not-export")
        result = crawl.build_catalog(CONFIG, fail, now=NOW)
        self.assertEqual(result["categories"][0]["items"], [])
        self.assertIsNone(result["categories"][0]["updated_at"])
        self.assertEqual(result["status"]["state"], "empty")
        self.assertNotIn("do-not-export", str(result))

    def test_successful_empty_search_does_not_label_old_items_as_fresh(self):
        result = crawl.build_catalog(CONFIG, lambda _: [], previous_catalog(), now=NOW)
        category = result["categories"][0]
        self.assertEqual(category["status"], "fresh")
        self.assertEqual(category["items"], [])
        self.assertEqual(category["updated_at"], NOW)

    def test_malformed_nonempty_result_preserves_last_good(self):
        result = crawl.build_catalog(CONFIG, lambda _: [{"bvid": "invalid"}],
                                     previous_catalog(), now=NOW)
        self.assertEqual(result["categories"][0]["status"], "stale")
        self.assertEqual(result["categories"][0]["items"][0]["bvid"], ITEM["bvid"])

    def test_risk_control_halts_remaining_queries(self):
        config = {"version": 1, "categories": [dict(CONFIG["categories"][0], queries=["QEMU", "FirmAE"])]}
        seen = []
        def fail(query):
            seen.append(query)
            raise crawl.CrawlError("Bilibili search API code -352", blocked=True)
        result = crawl.build_catalog(config, fail, previous_catalog(), now=NOW,
                                     sleep=lambda _: self.fail("must not retry risk control"))
        self.assertEqual(seen, ["QEMU"])
        self.assertEqual(result["status"]["queries_attempted"], 1)
        self.assertEqual(result["categories"][0]["status"], "stale")

    def test_partial_query_failure_keeps_old_items_with_new_results(self):
        config = {"version": 1, "categories": [dict(CONFIG["categories"][0], queries=["QEMU", "FirmAE"])]}
        def search(query):
            if query == "FirmAE":
                raise crawl.CrawlError("Bilibili search API code -352")
            return [dict(ITEM, bvid="BV1yy411c7mD", title="新的公开结果")]
        result = crawl.build_catalog(config, search, previous_catalog(), now=NOW, sleep=lambda _: None)
        self.assertEqual(result["categories"][0]["status"], "partial")
        self.assertEqual([item["bvid"] for item in result["categories"][0]["items"]],
                         ["BV1yy411c7mD", "BV1xx411c7mD"])

    def test_query_budget_is_round_robin(self):
        config = {"version": 1, "categories": [
            dict(CONFIG["categories"][0], queries=["QEMU", "FirmAE"]),
            {"id": "quantum", "name": "量子计算", "queries": ["量子计算", "量子算法"]},
        ]}
        seen = []
        def search(query):
            seen.append(query)
            return [ITEM]
        result = crawl.build_catalog(config, search, max_queries=2, sleep=lambda _: None, now=NOW)
        self.assertEqual(seen, ["QEMU", "量子计算"])
        self.assertEqual(result["status"]["queries_attempted"], 2)
        self.assertTrue(all(category["status"] == "partial" for category in result["categories"]))

    def test_rejects_invalid_bvid_and_untrusted_images(self):
        self.assertIsNone(crawl.sanitize_item(dict(ITEM, bvid="../../private")))
        for value in ["https://hdslb.com.evil.example/bfs/a.jpg", "javascript:alert(1)",
                      "https://name:password@i0.hdslb.com/bfs/a.jpg", "https://i0.hdslb.com/video/private.mp4"]:
            with self.subTest(value=value):
                self.assertEqual(crawl.safe_image(value), "")

    def test_duplicate_category_ids_fail_before_search(self):
        config = {"version": 1, "categories": CONFIG["categories"] * 2}
        with self.assertRaises(ValueError):
            crawl.build_catalog(config, lambda _: self.fail("must not request invalid config"))


if __name__ == "__main__":
    unittest.main()
