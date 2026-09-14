#!/usr/bin/env python3
"""Refresh a public Bilibili metadata catalog, retaining valid last-good data.

This job never logs into an account and never fetches or persists playback URLs.
API restrictions are reported as failures; there is no CAPTCHA or access bypass.
All dependencies are Python standard library modules.
"""

from __future__ import annotations

import argparse
import hashlib
import html
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
from datetime import datetime, timezone
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit, urlunsplit
from urllib.request import Request, urlopen


MIXIN_KEY_ORDER = (
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
    27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
    37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
    22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
)
API_ORIGIN = "https://api.bilibili.com"
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
BVID_RE = re.compile(r"BV[0-9A-Za-z]{10}\Z")
KEY_RE = re.compile(r"[0-9a-f]{32}\Z")
ID_RE = re.compile(r"[a-z][a-z0-9_]{0,63}\Z")
# These are optional public search statistics, not display abbreviations.
# video_review is the search API's danmaku count; review is the comment count.
PUBLIC_STATS = ("play", "pubdate", "video_review", "favorites")
MAX_PUBLIC_STAT = (1 << 63) - 1


class CrawlError(Exception):
    """A sanitized error safe to include in a public status document."""

    def __init__(self, message: str, *, blocked: bool = False) -> None:
        super().__init__(message)
        self.blocked = blocked


class _PlainText(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []

    def handle_data(self, data: str) -> None:
        self.parts.append(data)


def clean_text(value: Any, limit: int = 200) -> str:
    if not isinstance(value, str):
        return ""
    parser = _PlainText()
    parser.feed(value[:8000])
    return " ".join(html.unescape("".join(parser.parts)).split())[:limit]


def safe_image(value: Any) -> str:
    """Allow only public Bilibili image CDNs, never arbitrary/playback URLs."""
    if not isinstance(value, str) or len(value) > 3000:
        return ""
    if value.startswith("//"):
        value = "https:" + value
    try:
        parsed = urlsplit(value)
        host = (parsed.hostname or "").lower()
        if parsed.scheme not in ("http", "https") or parsed.username or parsed.password:
            return ""
        if parsed.port not in (None, 80, 443):
            return ""
        if not any(host == domain or host.endswith("." + domain)
                   for domain in ("hdslb.com", "biliimg.com")):
            return ""
        if not parsed.path.startswith("/bfs/") or len(parsed.path) > 2048:
            return ""
        # No query credentials or unrelated fragments enter the public catalog.
        return urlunsplit(("https", host, parsed.path, "", ""))
    except ValueError:
        return ""


def clean_duration(value: Any) -> str:
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if not 0 <= value <= 864000:
            return ""
        seconds = int(value)
        hours, seconds = divmod(seconds, 3600)
        minutes, seconds = divmod(seconds, 60)
        return f"{hours}:{minutes:02}:{seconds:02}" if hours else f"{minutes}:{seconds:02}"
    if not isinstance(value, str) or not re.fullmatch(r"\d{1,5}:\d{1,2}(?::\d{1,2})?", value):
        return ""
    components = value.split(":")
    if any(int(component) >= 60 for component in components[1:]):
        return ""
    return ":".join([str(int(components[0]))] + [f"{int(c):02}" for c in components[1:]])


def clean_public_stat(value: Any) -> int | None:
    """Keep exact counts/timestamps that the Android client can read as longs."""
    if isinstance(value, str):
        value = value.strip()
        if not re.fullmatch(r"[0-9]{1,19}", value):
            return None
        value = int(value)
    if type(value) is not int or not 0 <= value <= MAX_PUBLIC_STAT:
        return None
    return value


def sanitize_item(item: Any) -> dict[str, str | int] | None:
    if not isinstance(item, dict):
        return None
    bvid, title = item.get("bvid"), clean_text(item.get("title"))
    if not isinstance(bvid, str) or not BVID_RE.fullmatch(bvid) or not title:
        return None
    # Whitelist fields. In particular, no cookies, auth values, or play URLs.
    result: dict[str, str | int] = {
        "bvid": bvid,
        "title": title,
        "pic": safe_image(item.get("pic")),
        "author": clean_text(item.get("author"), 100),
        "duration": clean_duration(item.get("duration")),
    }
    for field in PUBLIC_STATS:
        value = clean_public_stat(item.get(field))
        if value is not None:
            result[field] = value
    return result


def sanitize_items(items: Any, limit: int = 30) -> list[dict[str, str | int]]:
    result: list[dict[str, str | int]] = []
    seen: set[str] = set()
    if not isinstance(items, list):
        return result
    for raw in items[:1000]:
        item = sanitize_item(raw)
        if item and item["bvid"] not in seen:
            seen.add(item["bvid"])
            result.append(item)
            if len(result) >= limit:
                break
    return result


def sign_wbi(params: dict[str, Any], img_key: str, sub_key: str,
             timestamp: int | None = None) -> dict[str, str]:
    """Sign normal public web API parameters with the current nav image keys."""
    if not KEY_RE.fullmatch(img_key) or not KEY_RE.fullmatch(sub_key):
        raise CrawlError("invalid public WBI keys")
    original = img_key + sub_key
    mixin = "".join(original[index] for index in MIXIN_KEY_ORDER)[:32]
    values = {str(key): re.sub(r"[!'()*]", "", str(value))
              for key, value in params.items() if key not in ("w_rid", "wts")}
    values["wts"] = str(int(time.time()) if timestamp is None else int(timestamp))
    values = dict(sorted(values.items()))
    values["w_rid"] = hashlib.md5((urlencode(values) + mixin).encode("utf-8")).hexdigest()
    return values


class PublicClient:
    def __init__(self, timeout: float = 12, attempts: int = 2,
                 sleep: Callable[[float], None] = time.sleep) -> None:
        self.timeout = timeout
        self.attempts = attempts
        self.sleep = sleep
        self._keys: tuple[str, str] | None = None

    def _get_json(self, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        url = API_ORIGIN + path + ("?" + urlencode(params) if params else "")
        request = Request(url, headers={
            "User-Agent": "Mozilla/5.0 (compatible; BilibiliPublicCatalog/1.0)",
            "Referer": "https://www.bilibili.com/",
            "Accept": "application/json",
        })
        for attempt in range(self.attempts):
            try:
                with urlopen(request, timeout=self.timeout) as response:
                    raw = response.read(MAX_RESPONSE_BYTES + 1)
                if len(raw) > MAX_RESPONSE_BYTES:
                    raise CrawlError("API response exceeds size limit")
                payload = json.loads(raw)
                if not isinstance(payload, dict):
                    raise CrawlError("API returned an unexpected JSON shape")
                return payload
            except HTTPError as exc:
                # Do not retry rate limits, authorization failures, or risk control.
                if exc.code < 500 or attempt + 1 == self.attempts:
                    raise CrawlError(f"Bilibili HTTP {exc.code}",
                                     blocked=exc.code in (401, 403, 412, 429)) from None
            except (URLError, TimeoutError, OSError):
                if attempt + 1 == self.attempts:
                    raise CrawlError("Bilibili connection failed or timed out") from None
            except (ValueError, UnicodeError):
                raise CrawlError("Bilibili returned non-JSON content") from None
            self.sleep(min(2 ** attempt, 4))
        raise CrawlError("Bilibili request failed")

    def public_keys(self) -> tuple[str, str]:
        if self._keys is None:
            payload = self._get_json("/x/web-interface/nav")
            # Unauthenticated nav legitimately has code -101 plus public wbi_img.
            data = payload.get("data")
            images = data.get("wbi_img") if isinstance(data, dict) else None
            if not isinstance(images, dict):
                raise CrawlError("Bilibili did not provide public WBI keys")
            keys = []
            for field in ("img_url", "sub_url"):
                url = images.get(field)
                key = Path(urlsplit(url).path).stem if isinstance(url, str) else ""
                if not KEY_RE.fullmatch(key):
                    raise CrawlError("Bilibili returned invalid public WBI keys")
                keys.append(key)
            self._keys = (keys[0], keys[1])
        return self._keys

    def search(self, keyword: str) -> list[dict[str, Any]]:
        img, sub = self.public_keys()
        params = sign_wbi({
            "search_type": "video", "keyword": keyword,
            "page": 1, "page_size": 20, "order": "totalrank",
        }, img, sub)
        payload = self._get_json("/x/web-interface/wbi/search/type", params)
        if payload.get("code") != 0:
            code = payload.get("code")
            code = code if isinstance(code, int) else "unknown"
            raise CrawlError(f"Bilibili search API code {code}",
                             blocked=code in (-101, -352, -412, -509))
        data = payload.get("data")
        if not isinstance(data, dict):
            raise CrawlError("Bilibili search response has no data object")
        items = data.get("result", [])
        if not isinstance(items, list):
            raise CrawlError("Bilibili search response has no result list")
        return items


def validate_config(config: Any) -> list[dict[str, Any]]:
    if not isinstance(config, dict) or config.get("version") != 1:
        raise ValueError("config must have version 1")
    categories = config.get("categories")
    if not isinstance(categories, list) or not 1 <= len(categories) <= 50:
        raise ValueError("config must contain between 1 and 50 categories")
    seen: set[str] = set()
    normalized = []
    for category in categories:
        if not isinstance(category, dict):
            raise ValueError("each category must be an object")
        category_id = category.get("id")
        if not isinstance(category_id, str) or not ID_RE.fullmatch(category_id) or category_id in seen:
            raise ValueError("category IDs must be unique lowercase identifiers")
        name = clean_text(category.get("name"), 80)
        queries = category.get("queries")
        if not name or not isinstance(queries, list) or not 1 <= len(queries) <= 2:
            raise ValueError("each category requires a name and one or two queries")
        if any(not isinstance(query, str) or not query.strip() or len(query) > 120 for query in queries):
            raise ValueError("queries must be nonempty strings up to 120 characters")
        seen.add(category_id)
        normalized.append({
            "id": category_id, "name": name,
            "description": clean_text(category.get("description"), 300),
            "queries": list(dict.fromkeys(query.strip() for query in queries)),
        })
    return normalized


def _timestamp(value: Any) -> str | None:
    if not isinstance(value, str) or len(value) > 40:
        return None
    try:
        date = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if date.tzinfo is None:
            return None
        return date.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    except ValueError:
        return None


def build_catalog(config: Any, search: Callable[[str], list[dict[str, Any]]],
                  previous: Any = None, *, max_queries: int | None = None,
                  limit: int = 30, delay: float = 1.5,
                  sleep: Callable[[float], None] = time.sleep,
                  now: str | None = None) -> dict[str, Any]:
    categories = validate_config(config)
    now = now or datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    if limit < 1 or (max_queries is not None and max_queries < 0) or delay < 0:
        raise ValueError("invalid crawler limits")
    old: dict[str, dict[str, Any]] = {}
    if isinstance(previous, dict) and previous.get("schema") == 1:
        previous_categories = previous.get("categories", [])
        if isinstance(previous_categories, list):
            old = {entry["id"]: entry for entry in previous_categories
                   if isinstance(entry, dict) and isinstance(entry.get("id"), str)}
    progress = {category["id"]: {"items": [], "successes": 0, "errors": []}
                for category in categories}
    attempted = 0
    blocked_error = None
    # Round robin gives every category a first query before any gets a second.
    for round_index in range(max(len(category["queries"]) for category in categories)):
        for category in categories:
            if round_index >= len(category["queries"]):
                continue
            state = progress[category["id"]]
            if blocked_error:
                state["errors"].append(blocked_error)
                continue
            if max_queries is not None and attempted >= max_queries:
                state["errors"].append("query budget exhausted")
                continue
            if attempted:
                sleep(delay)
            attempted += 1
            try:
                items = search(category["queries"][round_index])
                if not isinstance(items, list):
                    raise CrawlError("search returned an unexpected result shape")
                validated = sanitize_items(items, limit)
                if items and not validated:
                    raise CrawlError("search returned no valid video metadata")
                state["items"].extend(validated)
                state["successes"] += 1
            except CrawlError as exc:
                state["errors"].append(str(exc)[:160])
                if exc.blocked:
                    blocked_error = "refresh halted: " + str(exc)[:160]
            except Exception:
                # Avoid exporting an arbitrary exception's URL or credentials.
                state["errors"].append("unexpected search failure")
    result_categories = []
    for category in categories:
        category_id = category["id"]
        state, cached = progress[category_id], old.get(category_id, {})
        cached_items = sanitize_items(cached.get("items"), limit)
        fresh_items = sanitize_items(state["items"], limit)
        errors = list(dict.fromkeys(state["errors"]))
        result = dict(category)
        if state["successes"] and (fresh_items or not errors):
            result["status"] = "partial" if errors else "fresh"
            result["items"] = sanitize_items(fresh_items + (cached_items if errors else []), limit)
            result["updated_at"] = now
        elif cached_items:
            result.update(status="stale", items=cached_items,
                          updated_at=_timestamp(cached.get("updated_at")))
        else:
            result.update(status="empty", items=[], updated_at=None)
        if errors:
            result["error"] = "; ".join(errors)[:400]
        result_categories.append(result)
    statuses = [category["status"] for category in result_categories]
    has_items = any(category["items"] for category in result_categories)
    state = "ok" if all(status == "fresh" for status in statuses) else ("degraded" if has_items else "empty")
    return {
        "schema": 1, "generated_at": now, "categories": result_categories,
        "status": {
            "state": state,
            "live_categories": sum(status in ("fresh", "partial") for status in statuses),
            "stale_categories": statuses.count("stale"),
            "empty_categories": sum(not category["items"] for category in result_categories),
            "queries_attempted": attempted,
        },
    }


def read_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def write_json_atomic(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent,
                                         prefix=".catalog-", suffix=".json", delete=False) as handle:
            temporary = handle.name
            json.dump(value, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        os.replace(temporary, path)
    finally:
        if temporary and os.path.exists(temporary):
            os.unlink(temporary)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path("config/interests.json"))
    parser.add_argument("--output", type=Path, default=Path("public/catalog.json"))
    parser.add_argument("--previous", type=Path, help="previous catalog; defaults to output when present")
    parser.add_argument("--max-queries", type=int, default=None, help="total query budget; default: all queries")
    parser.add_argument("--limit", type=int, default=30, help="maximum public items per category (1-100)")
    parser.add_argument("--timeout", type=float, default=12, help="HTTP timeout seconds (1-30)")
    parser.add_argument("--attempts", type=int, default=2, help="HTTP attempts (1-3); no risk-control retries")
    parser.add_argument("--delay", type=float, default=1.5, help="delay between queries (minimum 0.5 seconds)")
    args = parser.parse_args(argv)
    if not 1 <= args.timeout <= 30 or not 1 <= args.attempts <= 3:
        parser.error("timeout must be 1-30 seconds and attempts must be 1-3")
    if not 1 <= args.limit <= 100 or args.delay < 0.5 or args.delay > 10:
        parser.error("limit must be 1-100 and delay must be 0.5-10 seconds")
    if args.max_queries is not None and args.max_queries < 0:
        parser.error("max-queries must be nonnegative")
    try:
        config = read_json(args.config)
        validate_config(config)
        previous_path = args.previous or args.output
        previous = None
        if previous_path.exists():
            try:
                previous = read_json(previous_path)
            except (OSError, ValueError):
                print("Previous catalog is unreadable; creating a fresh status document.", file=sys.stderr)
        client = PublicClient(timeout=args.timeout, attempts=args.attempts)
        catalog = build_catalog(config, client.search, previous, max_queries=args.max_queries,
                                limit=args.limit, delay=args.delay)
        write_json_atomic(args.output, catalog)
        print(json.dumps(catalog["status"], ensure_ascii=False))
        # A valid degraded catalog is publishable; never destroy last-good data.
        return 0
    except (OSError, ValueError) as exc:
        print(f"Cannot build catalog: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
