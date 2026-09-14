#!/usr/bin/env python3
"""Validate native plugin contents and public Pages artifacts without Android tools."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import sys
import zipfile
import zlib
from pathlib import Path
from urllib.parse import urlsplit

PRIVATE_KEYS = {"cookie", "cookies", "sessdata", "bili_jct", "access_token", "refresh_token", "qrcode_key"}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def validate_public_data(value: object, location: str = "JSON") -> None:
    """Reject account credentials in everything being prepared for publication."""
    if isinstance(value, dict):
        for key, child in value.items():
            require(str(key).lower() not in PRIVATE_KEYS, f"{location}: private credential field {key!r}")
            validate_public_data(child, f"{location}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            validate_public_data(child, f"{location}[{index}]")
    elif isinstance(value, str):
        require(not re.search(r"(?i)(?:SESSDATA|bili_jct|refresh_token)\s*=", value),
                f"{location}: private credential value")


def read_json(path: Path) -> object:
    value = json.loads(path.read_text(encoding="utf-8"))
    validate_public_data(value, str(path))
    return value


def dex_classes(data: bytes) -> set[str]:
    """Read class definitions, distinguishing bundled classes from references."""
    require(len(data) >= 112, "classes.dex is too short")
    require(bool(re.fullmatch(rb"dex\n0(?:3[5-9]|4[01])\x00", data[:8])), "invalid DEX magic/version")
    word = lambda offset: struct.unpack_from("<I", data, offset)[0]
    require(word(32) == len(data), "DEX file-size header does not match payload")
    require(word(36) >= 112 and word(40) == 0x12345678, "invalid DEX header")
    require(word(8) == zlib.adler32(data[12:]) & 0xFFFFFFFF, "DEX Adler32 checksum mismatch")
    require(data[12:32] == hashlib.sha1(data[32:]).digest(), "DEX signature mismatch")

    def table(count_offset: int, item_size: int) -> tuple[int, int]:
        count, offset = word(count_offset), word(count_offset + 4)
        require(offset + count * item_size <= len(data), "DEX table exceeds file size")
        return count, offset

    string_count, string_offset = table(56, 4)
    type_count, type_offset = table(64, 4)
    class_count, class_offset = table(96, 32)

    def descriptor(type_index: int) -> str:
        require(type_index < type_count, "DEX class has invalid type index")
        string_index = word(type_offset + 4 * type_index)
        require(string_index < string_count, "DEX type has invalid string index")
        offset = word(string_offset + 4 * string_index)
        require(offset < len(data), "DEX string offset exceeds file size")
        for _ in range(5):
            require(offset < len(data), "truncated DEX string length")
            current = data[offset]
            offset += 1
            if not current & 0x80:
                break
        else:
            raise ValueError("invalid DEX string length")
        end = data.find(b"\x00", offset)
        require(end >= 0, "unterminated DEX class descriptor")
        return data[offset:end].decode("utf-8")

    return {descriptor(word(class_offset + 32 * index)) for index in range(class_count)}


def verify_jar(path: Path) -> dict[str, object]:
    require(path.is_file(), f"plugin jar does not exist: {path}")
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "duplicate jar entries")
        require("classes.dex" in names, "plugin contains no classes.dex; JVM-only jars cannot run in TVBox")
        require(not any(name.endswith(".class") for name in names), "plugin contains unexpected JVM class files")
        require([name for name in names if name.endswith(".dex")] == ["classes.dex"], "plugin must contain one classes.dex")
        classes = dex_classes(archive.read("classes.dex"))
    require("Lcom/github/catvod/spider/BiliStudy;" in classes, "BiliStudy entrypoint missing from DEX")
    require("Lcom/google/zxing/qrcode/QRCodeWriter;" in classes, "QR encoder is missing from DEX")
    forbidden = ("Landroid/", "Lorg/json/", "Lcom/github/catvod/crawler/")
    require(not any(name.startswith(forbidden) for name in classes), "plugin bundles Android or TVBox host classes")
    content = path.read_bytes()
    return {"sha256": hashlib.sha256(content).hexdigest(), "md5": hashlib.md5(content).hexdigest(),
            "size": len(content), "classes": len(classes)}


def verify_site(root: Path) -> dict[str, object]:
    for name in ("index.html", "tvbox.json", "interests.json", "catalog.json", "build-info.json", ".nojekyll"):
        require((root / name).is_file(), f"published file missing: {name}")
    for path in root.rglob("*"):
        require(not path.is_symlink(), f"symlink cannot be published: {path}")
        if path.is_file() and path.suffix == ".json":
            read_json(path)
    config = read_json(root / "tvbox.json")
    info = read_json(root / "build-info.json")
    require(isinstance(config, dict) and isinstance(info, dict), "config/build info must be objects")
    spider_url, separator, expected_md5 = config.get("spider", "").partition(";md5;")
    require(bool(separator) and bool(re.fullmatch(r"[0-9a-f]{32}", expected_md5)), "TVBox spider checksum missing")
    url = urlsplit(spider_url)
    require(url.scheme == "https" and bool(url.netloc), "TVBox spider URL must be absolute HTTPS")
    jar_name = Path(url.path).name
    checksums = verify_jar(root / jar_name)
    require(checksums["md5"] == expected_md5, "TVBox jar MD5 mismatch")
    require(info.get("jar", {}).get("sha256") == checksums["sha256"], "published SHA256 mismatch")
    require(info.get("jar", {}).get("file") == jar_name, "build info points to a different jar")
    require(checksums["sha256"][:16] in jar_name, "jar URL is not content-versioned")
    sites = config.get("sites", [])
    require(len(sites) == 3 and len({site.get("key") for site in sites}) == 3, "expected three distinct TVBox modules")
    modes = set()
    base = spider_url.rsplit("/", 1)[0]
    for site in sites:
        require(site.get("type") == 3 and site.get("api") == "csp_BiliStudy", "invalid native TVBox site entry")
        extend = json.loads(site.get("ext", "{}"))
        validate_public_data(extend, "site.ext")
        modes.add(extend.get("mode"))
        require(extend.get("catalog") == base + "/catalog.json", "catalog URL is inconsistent")
        require(extend.get("interests") == base + "/interests.json", "interests URL is inconsistent")
    require(modes == {"media", "study", "search"}, "TVBox module modes missing")
    return checksums


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--site", type=Path)
    args = parser.parse_args()
    if not args.jar and not args.site:
        parser.error("provide --jar and/or --site")
    try:
        if args.jar:
            print("Verified native plugin:", json.dumps(verify_jar(args.jar), sort_keys=True))
        if args.site:
            print("Verified Pages publication:", json.dumps(verify_site(args.site), sort_keys=True))
    except (ValueError, OSError, zipfile.BadZipFile, struct.error, KeyError, TypeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc


if __name__ == "__main__":
    main()
