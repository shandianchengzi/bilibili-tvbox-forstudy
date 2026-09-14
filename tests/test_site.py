"""Artifact contract regressions; synthetic DEX fixtures are never published."""
import hashlib
import json
import struct
import tempfile
import unittest
import zipfile
import zlib
from pathlib import Path

from scripts.build_site import build_site, make_config, normalize_base_url
from scripts.verify import dex_classes, validate_public_data, verify_jar, verify_site


ENTRYPOINTS = ["Lcom/github/catvod/spider/BiliStudy;", "Lcom/google/zxing/qrcode/QRCodeWriter;"]


def dex_fixture(classes):
    """Minimal class-table fixture for our DEX metadata parser, not runnable code."""
    count = len(classes)
    strings_at, types_at, classes_at = 112, 112 + count * 4, 112 + count * 8
    data = bytearray(classes_at + count * 32)
    data[:8] = b"dex\n035\x00"
    put = lambda at, value: struct.pack_into("<I", data, at, value)
    put(36, 112)
    put(40, 0x12345678)
    put(56, count)
    put(60, strings_at)
    put(64, count)
    put(68, types_at)
    put(96, count)
    put(100, classes_at)
    for index, name in enumerate(classes):
        put(strings_at + index * 4, len(data))
        put(types_at + index * 4, index)
        put(classes_at + index * 32, index)
        data.extend(bytes([len(name)]) + name.encode() + b"\0")
    put(32, len(data))
    data[12:32] = hashlib.sha1(data[32:]).digest()
    put(8, zlib.adler32(data[12:]) & 0xFFFFFFFF)
    return bytes(data)


def write_fixture_jar(path, classes=ENTRYPOINTS):
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("classes.dex", dex_fixture(classes))


class NativeArtifactTests(unittest.TestCase):
    def test_reads_definitions_and_detects_corrupted_dex(self):
        payload = dex_fixture(ENTRYPOINTS)
        self.assertEqual(dex_classes(payload), set(ENTRYPOINTS))
        corrupt = payload[:-1] + b"x"
        with self.assertRaisesRegex(ValueError, "checksum"):
            dex_classes(corrupt)

    def test_rejects_jvm_jar_and_bundled_host(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "plugin.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("BiliStudy.class", b"not a native plugin")
            with self.assertRaisesRegex(ValueError, "classes.dex"):
                verify_jar(jar)
            write_fixture_jar(jar, ENTRYPOINTS + ["Lcom/github/catvod/crawler/Spider;"])
            with self.assertRaisesRegex(ValueError, "host classes"):
                verify_jar(jar)

    def test_rejects_missing_qr_dependency(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "plugin.jar"
            write_fixture_jar(jar, ENTRYPOINTS[:1])
            with self.assertRaisesRegex(ValueError, "QR encoder"):
                verify_jar(jar)


class PublicationTests(unittest.TestCase):
    def test_absolute_import_contract_and_modules(self):
        config = make_config("https://example.org/project/", "plugin.jar", "0" * 32)
        self.assertEqual(config["spider"], "https://example.org/project/plugin.jar;md5;" + "0" * 32)
        self.assertEqual({json.loads(site["ext"])["mode"] for site in config["sites"]},
                         {"media", "study", "search"})
        self.assertTrue(all(site["playerType"] == 2 for site in config["sites"]))
        for value in ("http://example.org", "https://u:p@example.org", "https://example.org/?key=1", "file:///tmp"):
            with self.assertRaises(ValueError):
                normalize_base_url(value)

    def test_rejects_credentials_deep_inside_catalog(self):
        for value in ({"categories": [{"items": [{"SESSDATA": "private"}]}]},
                      {"title": "url?bili_jct=private"}):
            with self.assertRaisesRegex(ValueError, "private credential"):
                validate_public_data(value)

    def test_build_verify_and_failed_rebuild_preserves_previous(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "site", root / "dist"
            source.mkdir()
            (source / "index.html").write_text("<!doctype html><title>TVBox</title>")
            interests, catalog, jar = root / "interests.json", root / "catalog.json", root / "plugin.jar"
            interests.write_text('{"version":1,"categories":[]}')
            catalog.write_text('{"schema_version":1,"categories":[],"status":{"state":"empty"}}')
            write_fixture_jar(jar)
            build_site(source, output, interests, catalog, jar, "https://example.org/project",
                       "revision", "2026-01-01T00:00:00Z")
            verified = verify_site(output)
            self.assertEqual(verified["sha256"], hashlib.sha256(jar.read_bytes()).hexdigest())
            original = (output / "tvbox.json").read_bytes()
            (source / "secrets.json").write_text('{"cookie":"private"}')
            with self.assertRaisesRegex(ValueError, "private credential"):
                build_site(source, output, interests, catalog, jar)
            self.assertEqual((output / "tvbox.json").read_bytes(), original)
            self.assertFalse(output.with_name("dist.staging").exists())

    def test_changed_jar_is_detected_after_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "site"
            source.mkdir()
            (source / "index.html").write_text("<!doctype html><title>TVBox</title>")
            for name in ("interests", "catalog"):
                (root / f"{name}.json").write_text('{}')
            jar = root / "plugin.jar"
            write_fixture_jar(jar)
            output = root / "dist"
            build_site(source, output, root / "interests.json", root / "catalog.json", jar)
            versioned = next(output.glob("bili-study.*.jar"))
            with zipfile.ZipFile(versioned, "a") as archive:
                archive.writestr("unexpected.txt", "modified")
            with self.assertRaisesRegex(ValueError, "MD5 mismatch"):
                verify_site(output)


if __name__ == "__main__":
    unittest.main()
