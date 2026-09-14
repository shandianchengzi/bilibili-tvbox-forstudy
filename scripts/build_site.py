#!/usr/bin/env python3
"""Assemble the static TVBox import endpoint and verify it before publication."""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

try:
    from .verify import read_json, require, verify_jar, verify_site
except ImportError:
    from verify import read_json, require, verify_jar, verify_site

DEFAULT_BASE_URL = "https://shandianchengzi.github.io/bilibili-tvbox-forstudy"
ASSET_SUFFIXES = {".html", ".css", ".js", ".svg", ".png", ".jpg", ".jpeg", ".webp", ".ico", ".txt", ".json"}


def normalize_base_url(base_url: str) -> str:
    url = urlsplit(base_url)
    require(url.scheme == "https" and bool(url.hostname), "base URL must use absolute HTTPS")
    require(not url.username and not url.password and not url.query and not url.fragment,
            "base URL cannot contain credentials, query parameters, or a fragment")
    require(not any(part in {".", ".."} for part in url.path.split("/")), "invalid base URL path")
    return base_url.rstrip("/")


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def make_config(base_url: str, jar_name: str, md5: str) -> dict[str, object]:
    base_url = normalize_base_url(base_url)
    modules = [("media", "Bilibili 影视", ""), ("study", "Bilibili 合集", ""),
               ("zhou_shen", "Bilibili 周深", "zhou-shen-"), ("account", "Bilibili 扫码登录", "")]
    return {
        "spider": f"{base_url}/{jar_name};md5;{md5}",
        "sites": [{
            "key": f"bili_study_{mode}", "name": name, "type": 3, "api": "csp_BiliStudy",
            "searchable": 0 if mode == "account" else 1, "quickSearch": 0,
            "filterable": 0 if mode == "account" else 1, "playerType": 2,
            "ext": json.dumps({"mode": mode} if mode == "account" else {
                "mode": mode, "catalog": f"{base_url}/{prefix}catalog.json",
                "interests": f"{base_url}/{prefix}interests.json"}, ensure_ascii=False, separators=(",", ":")),
        } for mode, name, prefix in modules],
        "parses": [], "lives": [], "flags": [],
    }


def build_site(source: Path, output: Path, interests_path: Path, catalog_path: Path,
               jar_path: Path, base_url: str = DEFAULT_BASE_URL, revision: str = "local",
               generated_at: str | None = None, *,
               zhou_shen_interests_path: Path = Path("config/zhou_shen.json"),
               zhou_shen_catalog_path: Path = Path("build/zhou-shen-catalog.json")) -> dict[str, object]:
    base_url = normalize_base_url(base_url)
    require(source.is_dir(), f"site asset directory missing: {source}")
    require((source / "index.html").is_file(), "site/index.html is required")
    source_root, output_root = source.resolve(), output.resolve()
    require(source_root != output_root and source_root not in output_root.parents
            and output_root not in source_root.parents, "output cannot overlap source assets")
    interests = read_json(interests_path)
    catalog = read_json(catalog_path)
    zhou_shen_interests = read_json(zhou_shen_interests_path)
    zhou_shen_catalog = read_json(zhou_shen_catalog_path)
    require(isinstance(interests, dict), "interests.json must be an object")
    require(isinstance(catalog, dict), "catalog.json must be an object")
    require(isinstance(zhou_shen_interests, dict), "zhou-shen-interests.json must be an object")
    require(isinstance(zhou_shen_catalog, dict), "zhou-shen-catalog.json must be an object")
    checksums = verify_jar(jar_path)
    jar_name = f"bili-study.{checksums['sha256'][:16]}.jar"
    if generated_at is None:
        epoch = os.environ.get("SOURCE_DATE_EPOCH")
        now = datetime.fromtimestamp(int(epoch), timezone.utc) if epoch else datetime.now(timezone.utc)
        generated_at = now.isoformat(timespec="seconds").replace("+00:00", "Z")
    info = {
        "schema_version": 1, "generated_at": generated_at, "source_revision": revision,
        "base_url": base_url, "config_url": base_url + "/tvbox.json",
        "jar": {**checksums, "file": jar_name, "url": base_url + "/" + jar_name},
        "catalog": {"generated_at": catalog.get("generated_at"), "status": catalog.get("status", "unknown")},
        "zhou_shen_catalog": {"generated_at": zhou_shen_catalog.get("generated_at"),
                              "status": zhou_shen_catalog.get("status", "unknown")},
    }

    # Stage all files so a failed validation cannot replace the previous local build.
    stage = output.with_name(output.name + ".staging")
    require(stage.resolve() != source.resolve(), "output overlaps source assets")
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)
    try:
        for path in sorted(source.rglob("*")):
            require(not path.is_symlink(), f"site assets cannot contain symlinks: {path}")
            if path.is_file():
                relative = path.relative_to(source)
                require(not any(part.startswith(".") for part in relative.parts), f"hidden site asset is not allowed: {path}")
                require(path.suffix.lower() in ASSET_SUFFIXES, f"unsupported site asset: {path}")
                target = stage / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
        shutil.copyfile(jar_path, stage / jar_name)
        # Stable jar alias is convenient for manual download. TVBox uses the versioned URL.
        shutil.copyfile(jar_path, stage / "bili-study.jar")
        write_json(stage / "interests.json", interests)
        write_json(stage / "catalog.json", catalog)
        write_json(stage / "zhou-shen-interests.json", zhou_shen_interests)
        write_json(stage / "zhou-shen-catalog.json", zhou_shen_catalog)
        write_json(stage / "tvbox.json", make_config(base_url, jar_name, str(checksums["md5"])))
        write_json(stage / "build-info.json", info)
        (stage / ".nojekyll").write_text("", encoding="utf-8")
        (stage / "SHA256SUMS").write_text(f"{checksums['sha256']}  {jar_name}\n", encoding="utf-8")
        verify_site(stage)
        if output.exists():
            shutil.rmtree(output)
        stage.rename(output)
    except Exception:
        shutil.rmtree(stage, ignore_errors=True)
        raise
    return info


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.environ.get("PAGES_BASE_URL", DEFAULT_BASE_URL))
    parser.add_argument("--source", type=Path, default=Path("site"))
    parser.add_argument("--output", type=Path, default=Path("dist/site"))
    parser.add_argument("--interests", type=Path, default=Path("config/interests.json"))
    parser.add_argument("--catalog", type=Path, default=Path("build/catalog.json"))
    parser.add_argument("--zhou-shen-interests", type=Path, default=Path("config/zhou_shen.json"))
    parser.add_argument("--zhou-shen-catalog", type=Path, default=Path("build/zhou-shen-catalog.json"))
    parser.add_argument("--jar", type=Path, default=Path("dist/bili-study.jar"))
    parser.add_argument("--revision", default=os.environ.get("GITHUB_SHA", "local"))
    args = parser.parse_args()
    try:
        info = build_site(args.source, args.output, args.interests, args.catalog, args.jar,
                          args.base_url, args.revision,
                          zhou_shen_interests_path=args.zhou_shen_interests,
                          zhou_shen_catalog_path=args.zhou_shen_catalog)
    except (ValueError, OSError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
    print(f"Built verified site at {args.output}; TVBox URL: {info['config_url']}")


if __name__ == "__main__":
    main()
