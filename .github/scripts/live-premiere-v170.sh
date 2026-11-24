#!/usr/bin/env bash
set -euo pipefail
ROOT='https://addisreel-catalog.eddieaman475.workers.dev'
UPDATER='https://addisreel-updates.eddieaman475.workers.dev/v1/latest'
TMP="$RUNNER_TEMP"

curl --retry 3 --retry-delay 2 -fsS "$ROOT/health/ready" |
  python3 -c 'import json,sys;x=json.load(sys.stdin); assert x["status"]=="ok" and "premiere-schedules" in x["checks"] and "exclusive-rights-registry" in x["checks"]'

curl --retry 3 --retry-delay 2 -fsS "$ROOT/v1/premieres" -o "$TMP/premiere-public.json"
python3 - "$TMP/premiere-public.json" <<'PY'
import json,sys,datetime
x=json.load(open(sys.argv[1]))
assert isinstance(x['premieres'],list) and len(x['premieres'])<=24
datetime.datetime.fromisoformat(x['serverNow'].replace('Z','+00:00'))
assert all('agreement_ref' not in i and 'creator_id' not in i and 'review_note' not in i for i in x['premieres'])
PY

curl --retry 3 --retry-delay 2 -fsS "$ROOT/admin/premieres.js" -o "$TMP/creator-premiere-client.js"
grep -q 'scheduleEpisode' "$TMP/creator-premiere-client.js"
grep -q 'schedulePremiere' "$TMP/creator-premiere-client.js"
test "$(curl -sS -o /dev/null -w '%{http_code}' "$ROOT/v1/admin/premieres")" = 401
test "$(curl -sS -o /dev/null -w '%{http_code}' "$ROOT/admin/premieres")" = 302
test "$(curl -sS -o /dev/null -w '%{http_code}' -X PUT -H 'Origin: https://evil.example' -H 'Content-Type: application/json' -d '{"action":"schedule"}' "$ROOT/v1/admin/premieres/unknown")" = 403

curl --retry 3 --retry-delay 2 -fsS "$ROOT/v1/catalog" -o "$TMP/catalog-v170.json"
python3 - "$TMP/catalog-v170.json" <<'PY'
import json,sys
x=json.load(open(sys.argv[1]))
assert isinstance(x['series'],list) and len(x['series'])>=1, 'Previously playable title unavailable'
PY

curl --retry 3 --retry-delay 2 -fsS "$UPDATER" |
  python3 -c 'import json,sys;x=json.load(sys.stdin);assert x["versionCode"]>=25, "Build 25 must be publicly installed before Build 26"'
echo '### Phase 3 and Premium 1.7.0 production preflight: green' >> "$GITHUB_STEP_SUMMARY"
