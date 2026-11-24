#!/usr/bin/env bash
set -euo pipefail

ROOT='https://addisreel-catalog.eddieaman475.workers.dev'
UPDATES='https://addisreel-updates.eddieaman475.workers.dev/v1/latest'
DIR="$RUNNER_TEMP"

curl --retry 3 --retry-delay 2 -fsS "$ROOT/health/ready" |
  python3 -c 'import json,sys;x=json.load(sys.stdin);assert x["status"]=="ok" and "exclusive-rights-registry" in x["checks"]'

curl --retry 3 --retry-delay 2 -fsS "$ROOT/admin/client.js" -o "$DIR/studio-client.js"
curl --retry 3 --retry-delay 2 -fsS "$ROOT/admin/creators.js" -o "$DIR/creator-client.js"
curl --retry 3 --retry-delay 2 -fsS "$ROOT/admin/rights.js" -o "$DIR/rights-client.js"
grep -q 'uploadLargeMp4' "$DIR/studio-client.js"
grep -q 'reviewHistory' "$DIR/creator-client.js"
grep -q 'reviewDecision' "$DIR/rights-client.js"

OWNER_STATUS=$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' "$ROOT/v1/admin/creators")
RIGHTS_STATUS=$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' "$ROOT/v1/admin/rights")
PRIVATE_PAGE=$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' "$ROOT/admin/rights")
FORGED=$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' -X POST \
  -H 'Origin: https://untrusted.example' -H 'Content-Type: application/json' -d '{}' "$ROOT/v1/admin/rights")
test "$OWNER_STATUS" = '401'
test "$RIGHTS_STATUS" = '401'
test "$PRIVATE_PAGE" = '302'
test "$FORGED" = '403'

curl --retry 3 --retry-delay 2 -fsS -D "$DIR/catalog-headers" "$ROOT/v1/catalog" -o "$DIR/catalog.json"
python3 - "$DIR/catalog.json" <<'PY'
import json,sys
titles=json.load(open(sys.argv[1]))['series']
assert isinstance(titles,list) and titles, 'Previously released series unexpectedly missing'
assert all('agreement_ref' not in title and 'creator_id' not in title for title in titles)
PY

curl --retry 3 --retry-delay 2 -fsS "$UPDATES" |
  python3 -c 'import json,sys;x=json.load(sys.stdin);assert x["versionCode"]>=24'

echo '### Native Creator Studio backend ready, owner sessions and exclusive rights protected' >> "$GITHUB_STEP_SUMMARY"
