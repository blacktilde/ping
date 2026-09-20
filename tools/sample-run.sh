#!/usr/bin/env bash
# The runner's CI gate: drives a ping-core binary against a loopback server.
#
#   tools/sample-run.sh <path to ping-core or its launch script>
#
# Run it against the JVM launcher and against the native image; both must behave the same.
# A deliberately failing collection has to produce a non-zero exit, or CI would go green on
# a broken assertion.
set -u

BIN=${1:?usage: sample-run.sh <ping-core binary>}
PORT=${PORT:-18099}
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
BASE="http://127.0.0.1:$PORT"

python3 "$HERE/sample/server.py" "$PORT" &
SERVER=$!
trap 'kill $SERVER 2>/dev/null; rm -rf "$WORK"' EXIT

for _ in $(seq 1 50); do
  (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null && break
  sleep 0.1
done

failures=0
expect() { # expect <exit code> <label> <command...>
  local want=$1 label=$2
  shift 2
  "$@" >"$WORK/out" 2>"$WORK/err"
  local got=$?
  if [ "$got" -eq "$want" ]; then
    echo "ok   $label (exit $got)"
  else
    echo "FAIL $label: wanted exit $want, got $got"
    sed 's/^/     out: /' "$WORK/out"
    sed 's/^/     err: /' "$WORK/err"
    failures=$((failures + 1))
  fi
}

expect 0 "passing collection passes" \
  "$BIN" run "$HERE/sample/passing" -e ci --var "baseUrl=$BASE"
# Whoami answers 200 only for the token Login returned, so this also proves a captured value
# reached the next request. The captured value itself must never appear in a report.
for reporter in json junit human; do
  "$BIN" run "$HERE/sample/passing" -e ci --var "baseUrl=$BASE" -r "$reporter" -o "$WORK/report-$reporter" >/dev/null 2>&1
  if grep -q 'tok-sample-4711\|sample-cookie-8842' "$WORK/report-$reporter"; then
    echo "FAIL the $reporter report contains a captured value or a cookie"
    failures=$((failures + 1))
  else
    echo "ok   the $reporter report holds no captured value or cookie"
  fi
done
expect 1 "a failing assertion fails the run" \
  "$BIN" run "$HERE/sample/failing" -e ci --var "baseUrl=$BASE"
expect 1 "junit report of a failing run" \
  "$BIN" run "$HERE/sample/failing" -e ci --var "baseUrl=$BASE" -r junit -o "$WORK/report.xml"
grep -q '<failure' "$WORK/report.xml" || { echo "FAIL junit report has no <failure>"; failures=$((failures + 1)); }
expect 1 "an unreachable server is an error, not a pass" \
  "$BIN" run "$HERE/sample/passing" -e ci --var "baseUrl=http://127.0.0.1:1"
expect 2 "an unknown environment cannot start a run" \
  "$BIN" run "$HERE/sample/passing" -e nope

# No arguments must still mean the stdio RPC loop: that is how the desktop shell spawns it.
rpc=$(echo '{"jsonrpc":"2.0","id":1,"method":"core.ping"}' | "$BIN" 2>/dev/null)
if echo "$rpc" | grep -q '"id":1' && echo "$rpc" | grep -q 'core.ready'; then
  echo "ok   no arguments serves JSON-RPC"
else
  echo "FAIL no arguments did not serve JSON-RPC: $rpc"
  failures=$((failures + 1))
fi

[ "$failures" -eq 0 ] && echo "all runner checks passed" || { echo "$failures runner check(s) failed"; exit 1; }
