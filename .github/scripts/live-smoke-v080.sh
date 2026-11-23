#!/usr/bin/env bash
# Production smoke checks: public catalog, poster fallback, ETag, and admin isolation.
set -Eeuo pipefail
BASE="${ADDISREEL_CATALOG_URL:-https://addisreel-catalog.eddieaman475.workers.dev}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fsS --retry 2 --retry-delay 1 --max-time 30 "$BASE/health" -o "$TMP/health.json"
python3 - "$TMP/health.json" <<'PY'
import json,sys
data=json.load(open(sys.argv[1]))
assert data.get('status')=='ok' and data.get('service')=='addisreel-catalog', data
print('✓ Live health OK')
PY
curl -fsS --retry 2 --retry-delay 1 --max-time 30 "$BASE/v1/catalog" -o "$TMP/catalog.json"
SERIES_ID="$(python3 - "$TMP/catalog.json" <<'PY'
import json,re,sys
data=json.load(open(sys.argv[1]))
assert isinstance(data.get('series'),list), data
print(next((s['id'] for s in data['series'] if re.fullmatch(r'[a-z0-9-]+',s.get('id',''))),''), end='')
PY
)"
if [[ -z "$SERIES_ID" ]]; then
  echo "::error::No published drama available for poster smoke test"
  exit 1
fi
echo "✓ Live catalog OK; testing series $SERIES_ID"
STATUS="$(curl -sS --retry 2 --retry-delay 1 --max-time 40 -o "$TMP/poster" -D "$TMP/poster.headers" -w '%{http_code}' "$BASE/v1/posters/$SERIES_ID?w=320")"
[[ "$STATUS" == 200 ]] || { echo "::error::Poster returned $STATUS"; cat "$TMP/poster.headers"; exit 1; }
python3 - "$TMP/poster.headers" "$TMP/poster" <<'PY'
import sys,pathlib,re
headers=pathlib.Path(sys.argv[1]).read_text().lower()
size=pathlib.Path(sys.argv[2]).stat().st_size
assert size>0,'Poster response empty'
assert 'etag:' in headers,'Missing ETag'
assert 'cache-control:' in headers,'Missing Cache-Control'
assert 'server-timing:' in headers,'Missing Server-Timing'
variant=re.search(r'(?m)^x-poster-variant:\s*(\S+)',headers)
assert variant and variant.group(1) in ('original','webp-320'),headers
print(f'✓ Poster endpoint OK: {variant.group(1)}, {size} bytes')
if variant.group(1)=='original':
  print('::warning::Legacy poster is served as its original file; migrate to WebP variants for full performance benefit.')
PY
BAD="$(curl -sS --retry 2 --max-time 30 -o /dev/null -w '%{http_code}' "$BASE/v1/posters/$SERIES_ID?w=321")"
[[ "$BAD" == 400 ]] || { echo "::error::Invalid-width request returned $BAD, expected 400"; exit 1; }
ADMIN="$(curl -sS --retry 2 --max-time 30 -o /dev/null -w '%{http_code}' "$BASE/v1/admin/series")"
[[ "$ADMIN" == 401 ]] || { echo "::error::Anonymous admin request returned $ADMIN, expected 401"; exit 1; }
echo '✓ Invalid widths rejected and anonymous admin access denied'
