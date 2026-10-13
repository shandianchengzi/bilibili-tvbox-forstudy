#!/usr/bin/env bash
set -euo pipefail
if [[ -z "${CLOUDFLARE_API_TOKEN:-}" || -z "${CLOUDFLARE_ACCOUNT_ID:-}" ]]; then
  echo "::warning::Cloudflare mirror NOT deployed: configure CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID repository Actions secrets."
  echo "⚠️ Cloudflare mirror not configured." >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  exit 0
fi
if [[ ! -d "$MIRROR_DIR" ]]; then echo "::error::Missing site directory: $MIRROR_DIR"; exit 1; fi
if [[ ! "$MIRROR_PROJECT" =~ ^[a-z0-9][a-z0-9-]*$ || ! "$MIRROR_PREFIX" =~ ^[a-zA-Z0-9._-]*$ ]]; then
  echo "::error::Invalid project name or site prefix"; exit 1
fi
if [[ "$MIRROR_LARGE_POLICY" != fail && "$MIRROR_LARGE_POLICY" != skip ]]; then exit 1; fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
stage="$tmp/site"
dest="$stage"
mkdir -p "$stage"
if [[ -n "$MIRROR_PREFIX" ]]; then
  dest="$stage/$MIRROR_PREFIX"
  mkdir -p "$dest"
  printf '%s\n' "<!doctype html><html><meta charset=\"utf-8\"><meta http-equiv=\"refresh\" content=\"0;url=/$MIRROR_PREFIX/\"><title>Site mirror</title><a href=\"/$MIRROR_PREFIX/\">Open website</a></html>" > "$stage/index.html"
fi
tar -C "$MIRROR_DIR" --exclude="./.git" --exclude="./.github" -cf - . | tar -C "$dest" -xf -

oversized=0
while IFS= read -r -d '' filename; do
  oversized=$((oversized+1))
  rel="${filename#"$stage/"}"
  if [[ "$MIRROR_LARGE_POLICY" == skip ]]; then
    echo "::warning::Excluding asset over 25 MiB (Cloudflare Pages limit): $rel"
    rm -f "$filename"
  else
    echo "::error::Asset exceeds Cloudflare Pages 25 MiB limit: $rel"
  fi
done < <(find "$stage" -type f -size +26214400c -print0)
if (( oversized > 0 )) && [[ "$MIRROR_LARGE_POLICY" == fail ]]; then exit 1; fi

if [[ "$MIRROR_PREFIX" == cut-player && "$oversized" -gt 0 ]]; then
  rm -f "$dest/updates/latest.yml" "$dest/updates/"*.blockmap
  mkdir -p "$dest/updates"
  cat > "$dest/updates/index.html" <<'EOF'
<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Cut Player · 备用页面</title><h1>Cut Player 备用说明页</h1>
<p>本镜像保留了工具介绍，但 Windows 安装包超过 Cloudflare Pages 单文件 25 MiB 限制，未提供安装包或自动更新文件。</p>
<p>安装包请前往 <a href="https://github.com/shandianchengzi/cut-player/releases/latest">GitHub Releases</a>。如果 GitHub 无法访问，该下载链接也可能失效。</p><p><a href="../">返回工具介绍</a></p>
EOF
fi

if [[ -n "$MIRROR_PREFIX" ]]; then
  MIRROR_STAGE="$stage" MIRROR_SITE="$MIRROR_PREFIX" MIRROR_HOST="$MIRROR_PROJECT" python3 - <<'PY'
import os
from pathlib import Path
site = os.environ['MIRROR_SITE']
old = f'https://shandianchengzi.github.io/{site}'.encode()
new = f'https://{os.environ["MIRROR_HOST"]}.pages.dev/{site}'.encode()
ext = {'.html','.htm','.css','.js','.mjs','.json','.svg','.xml','.txt','.webmanifest','.md','.yml','.yaml'}
for file in Path(os.environ['MIRROR_STAGE']).rglob('*'):
    if file.is_file() and file.suffix.lower() in ext:
        data = file.read_bytes()
        if old in data: file.write_bytes(data.replace(old,new))
PY
fi

count="$(find "$stage" -type f | wc -l)"
if (( count > 20000 )); then echo "::error::Exceeds Cloudflare Pages Free 20,000 file limit: $count"; exit 1; fi
if [[ ! -f "$dest/index.html" ]]; then echo "::error::No index.html at $MIRROR_DIR"; exit 1; fi

status="$(curl --silent --show-error --output "$tmp/project.json" --write-out '%{http_code}' \
  --header "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
  "https://api.cloudflare.com/client/v4/accounts/$CLOUDFLARE_ACCOUNT_ID/pages/projects/$MIRROR_PROJECT")"
case "$status" in
  200) echo "Using existing Cloudflare Pages project: $MIRROR_PROJECT" ;;
  404) npx --yes wrangler@4 pages project create "$MIRROR_PROJECT" --production-branch="$MIRROR_BRANCH" ;;
  *) echo "::error::Cloudflare project lookup returned HTTP $status (check token, account ID, permissions)"; exit 1 ;;
esac
npx --yes wrangler@4 pages deploy "$stage" --project-name "$MIRROR_PROJECT" --branch "$MIRROR_BRANCH" --commit-hash "$GITHUB_SHA"
echo "✅ Cloudflare backup: https://$MIRROR_PROJECT.pages.dev/${MIRROR_PREFIX:+$MIRROR_PREFIX/}" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
if (( oversized > 0 )); then echo "⚠️ Excluded $oversized files over 25 MiB: not a complete binary backup." >> "${GITHUB_STEP_SUMMARY:-/dev/null}"; fi
