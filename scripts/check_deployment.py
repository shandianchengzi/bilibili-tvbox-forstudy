#!/usr/bin/env python3
"""Check the actual Pages responses after publication, including the loaded DEX hash."""
import hashlib
import io
import json
import os
import time
import urllib.request
import zipfile

try:
    from .verify import ZHOU_SHEN_CATEGORIES, verify_catalog_pair
except ImportError:
    from verify import ZHOU_SHEN_CATEGORIES, verify_catalog_pair


def read(url, limit=8 * 1024 * 1024):
    request = urllib.request.Request(url, headers={"User-Agent": "BiliStudy-DeploymentCheck/1.0", "Cache-Control": "no-cache"})
    with urllib.request.urlopen(request, timeout=15) as response:
        body = response.read(limit + 1)
        if response.status != 200 or len(body) > limit:
            raise ValueError("Unexpected HTTP status or oversized public artifact")
        return body


def check(base, revision):
    suffix = "?revision=" + revision
    info = json.loads(read(base + "/build-info.json" + suffix))
    if info.get("source_revision") != revision:
        raise ValueError("Pages has not exposed the requested revision yet")
    config = json.loads(read(base + "/tvbox.json" + suffix))
    if len(config["sites"]) != 3 or {site["key"] for site in config["sites"]} != {
            "bili_study_media", "bili_study_study", "bili_study_zhou_shen"}:
        raise ValueError("Published TVBox modules are incomplete")
    for site in config["sites"]:
        extend = json.loads(site["ext"])
        mode = site["key"].removeprefix("bili_study_")
        prefix = "zhou-shen-" if mode == "zhou_shen" else ""
        if (site.get("searchable") != 1 or extend.get("mode") != mode
                or extend.get("catalog") != f"{base}/{prefix}catalog.json"
                or extend.get("interests") != f"{base}/{prefix}interests.json"):
            raise ValueError("Published TVBox module search or catalog routing is invalid")
    jar_url, separator, expected_md5 = config["spider"].partition(";md5;")
    if not separator or jar_url != base + "/" + info["jar"]["file"]:
        raise ValueError("Published plugin URL does not match the build manifest")
    plugin = read(jar_url)
    if hashlib.sha256(plugin).hexdigest() != info["jar"]["sha256"] or hashlib.md5(plugin).hexdigest() != expected_md5:
        raise ValueError("Published plugin checksum mismatch")
    with zipfile.ZipFile(io.BytesIO(plugin)) as archive:
        if not archive.read("classes.dex").startswith(b"dex\n"):
            raise ValueError("Published plugin is not a native DEX JAR")
    interests = json.loads(read(base + "/interests.json" + suffix))
    catalog = json.loads(read(base + "/catalog.json" + suffix))
    verify_catalog_pair(interests, catalog, "study")
    zhou_shen_interests = json.loads(read(base + "/zhou-shen-interests.json" + suffix))
    zhou_shen_catalog = json.loads(read(base + "/zhou-shen-catalog.json" + suffix))
    verify_catalog_pair(zhou_shen_interests, zhou_shen_catalog, "zhou_shen", ZHOU_SHEN_CATEGORIES)
    if b"<html" not in read(base + "/" + suffix).lower():
        raise ValueError("Landing page is missing")
    print(json.dumps({"deployment": "verified", "revision": revision, "config_url": base + "/tvbox.json", "plugin_sha256": info["jar"]["sha256"], "catalog_status": catalog["status"], "zhou_shen_catalog_status": zhou_shen_catalog["status"]}, ensure_ascii=False))


if __name__ == "__main__":
    base = os.environ["PAGES_BASE_URL"].rstrip("/")
    revision = os.environ["GITHUB_SHA"]
    for attempt in range(6):
        try:
            check(base, revision)
            break
        except Exception as error:
            if attempt == 5:
                raise
            print("Waiting for published Pages responses:", type(error).__name__, flush=True)
            time.sleep(5)
