#!/usr/bin/env bash
# End-to-end check against a running backend: health, sign-up, a challenge, and a sandboxed test run.
#   ./deploy/ec2/smoke-test.sh http://<public-ip>:8080
set -euo pipefail
API="${1:?usage: smoke-test.sh http://<host>:8080}/api"
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }

echo -n "health: "; curl -fs "$API/health"; echo
EMAIL="smoke-$(date +%s)@test.dev"
TOKEN=$(curl -fs -X POST "$API/auth/register" -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"smoke-test-1\"}" | json 'd["token"]')
echo "signed up: $EMAIL"
START=$(curl -fs -X POST "$API/challenges/next" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"language":"java","concept":"polymorphism"}')
ID=$(echo "$START" | json 'd["attemptId"]'); echo "challenge: $(echo "$START" | json 'd["challenge"]["title"]') (attempt $ID)"
BODY=$(echo "$START" | python3 -c "import sys,json; d=json.load(sys.stdin); print(json.dumps({'attemptId': d['attemptId'], 'files': d['challenge']['files'], 'mode': 'tests'}))")
echo "running the starter code against hidden tests in the sandbox..."
curl -fs -X POST "$API/run" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$BODY" \
  | json '"compiled=%s  passed=%d/%d  time=%dms  %s" % (d["compiled"], sum(t["passed"] for t in d["tests"]), len(d["tests"]), d["millis"], (d["diagnostics"][0]["message"] if d["diagnostics"] else ""))'
echo "Expected for a healthy sandbox: compiled=True and some tests failing (it is unsolved starter code)."
