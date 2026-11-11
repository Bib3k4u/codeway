#!/usr/bin/env bash
# End-to-end check against a running backend: health, auth enforcement and, with a token, a sandboxed test run.
#   ./deploy/ec2/smoke-test.sh https://<backend-host>
#   TOKEN=<supabase access token> ./deploy/ec2/smoke-test.sh https://<backend-host>
# Sign-in is GitHub only, so a token has to come from a real session: sign in on the site, then in the browser
# console run:  JSON.parse(localStorage.getItem(Object.keys(localStorage).find(k => k.endsWith("-auth-token")))).access_token
set -euo pipefail
API="${1:?usage: smoke-test.sh https://<backend-host>}/api"
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }

echo -n "health: "; curl -fs "$API/health"; echo
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$API/auth/me" -H "Authorization: Bearer not-a-real-token")
echo "forged token -> $CODE (expect 401)"; [ "$CODE" = 401 ]
if [ -z "${TOKEN:-}" ]; then echo "No TOKEN set: skipping the sandbox run."; exit 0; fi
echo "signed in as: $(curl -fs "$API/auth/me" -H "Authorization: Bearer $TOKEN" | json 'd["email"]')"
START=$(curl -fs -X POST "$API/challenges/next" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"language":"java","concept":"polymorphism"}')
ID=$(echo "$START" | json 'd["attemptId"]'); echo "challenge: $(echo "$START" | json 'd["challenge"]["title"]') (attempt $ID)"
BODY=$(echo "$START" | python3 -c "import sys,json; d=json.load(sys.stdin); print(json.dumps({'attemptId': d['attemptId'], 'files': d['challenge']['files'], 'mode': 'tests'}))")
echo "running the starter code against hidden tests in the sandbox..."
curl -fs -X POST "$API/run" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$BODY" \
  | json '"compiled=%s  passed=%d/%d  time=%dms  %s" % (d["compiled"], sum(t["passed"] for t in d["tests"]), len(d["tests"]), d["millis"], (d["diagnostics"][0]["message"] if d["diagnostics"] else ""))'
echo "Expected for a healthy sandbox: compiled=True and some tests failing (it is unsolved starter code)."
