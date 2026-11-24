#!/usr/bin/env bash
# The runner's CI gate: drives a ping-core binary against a loopback server.
#
#   tools/sample-run.sh <path to ping-core or its launch script>
#
# Run it against the JVM launcher and against the native image; both must behave the same.
# A deliberately failing collection has to produce a non-zero exit, or CI would go green on
# a broken assertion.
set -u
# A proxy in the developer's own environment must not leak into the runs that expect none.
unset HTTP_PROXY HTTPS_PROXY ALL_PROXY NO_PROXY http_proxy https_proxy all_proxy no_proxy

BIN=${1:?usage: sample-run.sh <ping-core binary>}
# Ports are picked free per run: the self-hosted runners share a host, so fixed ports let two
# jobs collide, and the loser's server dies at bind and every request is refused.
free_port() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])'; }
PORT=${PORT:-$(free_port)}
PROXY_PORT=${PROXY_PORT:-$(free_port)}
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
BASE="http://127.0.0.1:$PORT"

python3 "$HERE/sample/server.py" "$PORT" 2>"$WORK/server.err" &
SERVER=$!
PROXYLOG="$WORK/proxy.log"
: >"$PROXYLOG"
python3 "$HERE/sample/proxy.py" "$PROXY_PORT" "$PROXYLOG" 2>"$WORK/proxy.err" &
PROXY=$!
trap 'kill $SERVER $PROXY 2>/dev/null; rm -rf "$WORK"' EXIT

# A helper that never came up must stop the gate with its own error, not show up later as
# a wall of "Connection refused" that looks like a runner bug.
wait_for() { # wait_for <name> <port> <pid> <stderr file>
  for _ in $(seq 1 50); do
    # Open AND still ours: another process holding the port would answer while ours died at bind.
    if (exec 3<>"/dev/tcp/127.0.0.1/$2") 2>/dev/null; then
      sleep 0.3
      kill -0 "$3" 2>/dev/null && return 0
      break
    fi
    kill -0 "$3" 2>/dev/null || break
    sleep 0.1
  done
  echo "FAIL the sample $1 is not listening on 127.0.0.1:$2"
  sed 's/^/     err: /' "$4"
  exit 1
}
wait_for server "$PORT" "$SERVER" "$WORK/server.err"
wait_for proxy "$PROXY_PORT" "$PROXY" "$WORK/proxy.err"

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

# The proxy: the same collection, through a forwarding proxy. Loopback is not exempt, so a run
# that passes here and shows up in the proxy's log really went through it.
expect 0 "the passing collection passes through --proxy" \
  "$BIN" run "$HERE/sample/passing" -e ci --var "baseUrl=$BASE" --proxy "127.0.0.1:$PROXY_PORT" --proxy-user "ci:sample-proxy-pw"
if [ "$(grep -c "^GET $BASE/health " "$PROXYLOG")" -ge 1 ] && grep -q ' Basic ' "$PROXYLOG"; then
  echo "ok   the proxy saw the requests and its credentials"
else
  echo "FAIL the proxy did not see the run:"; sed 's/^/     log: /' "$PROXYLOG"
  failures=$((failures + 1))
fi
: >"$PROXYLOG"
HTTP_PROXY="http://127.0.0.1:$PROXY_PORT" expect 0 "HTTP_PROXY applies with no flags" \
  "$BIN" run "$HERE/sample/passing" -e ci --var "baseUrl=$BASE"
[ -s "$PROXYLOG" ] && echo "ok   HTTP_PROXY routed the run through the proxy" \
  || { echo "FAIL HTTP_PROXY did not route the run"; failures=$((failures + 1)); }
: >"$PROXYLOG"
HTTP_PROXY="http://127.0.0.1:$PROXY_PORT" expect 0 "--no-proxy ignores HTTP_PROXY" \
  "$BIN" run "$HERE/sample/passing" -e ci --var "baseUrl=$BASE" --no-proxy
[ -s "$PROXYLOG" ] && { echo "FAIL --no-proxy still used the proxy"; failures=$((failures + 1)); } \
  || echo "ok   --no-proxy kept the run off the proxy"
expect 2 "an unsupported proxy cannot start a run" \
  "$BIN" run "$HERE/sample/passing" -e ci --var "baseUrl=$BASE" --proxy "socks5://127.0.0.1:1080"

# No arguments must still mean the stdio RPC loop: that is how the desktop shell spawns it.
rpc=$(echo '{"jsonrpc":"2.0","id":1,"method":"core.ping"}' | "$BIN" 2>/dev/null)
if echo "$rpc" | grep -q '"id":1' && echo "$rpc" | grep -q 'core.ready'; then
  echo "ok   no arguments serves JSON-RPC"
else
  echo "FAIL no arguments did not serve JSON-RPC: $rpc"
  failures=$((failures + 1))
fi

[ "$failures" -eq 0 ] && echo "all runner checks passed" || { echo "$failures runner check(s) failed"; exit 1; }
