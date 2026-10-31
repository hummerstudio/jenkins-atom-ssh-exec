#!/usr/bin/env bash
# A real sshd, so the quoting, the exit code and the timeout are all exercised for real.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

sshd_dir="$(mktemp -d)"; port=2223; user="$(whoami)"
cleanup() { [ -f "$sshd_dir/sshd.pid" ] && kill "$(cat "$sshd_dir/sshd.pid")" 2>/dev/null; }
trap cleanup EXIT

ssh-keygen -q -t ed25519 -N '' -f "$sshd_dir/host_key"
ssh-keygen -q -t ed25519 -N '' -f "$sshd_dir/user_key"
cp "$sshd_dir/user_key.pub" "$sshd_dir/authorized_keys"; chmod 600 "$sshd_dir/authorized_keys"
cat >"$sshd_dir/sshd_config" <<EOF
Port $port
ListenAddress 127.0.0.1
HostKey $sshd_dir/host_key
PidFile $sshd_dir/sshd.pid
AuthorizedKeysFile $sshd_dir/authorized_keys
PasswordAuthentication no
UsePAM no
StrictModes no
EOF
/usr/sbin/sshd -f "$sshd_dir/sshd_config" -E "$sshd_dir/sshd.log" || fail "could not start the throwaway sshd"
ok=""
for _ in $(seq 1 40); do
  if ssh -p $port -i "$sshd_dir/user_key" -o BatchMode=yes -o StrictHostKeyChecking=no \
     -o UserKnownHostsFile=/dev/null "$user@127.0.0.1" true 2>/dev/null; then ok=1; break; fi
  sleep 0.2
done
[ -n "$ok" ] || fail "the throwaway sshd never accepted the key"

ws="$(mktemp -d)"; cp "$sshd_dir/user_key" "$ws/deploy_key"; chmod 600 "$ws/deploy_key"

run_exec() { # run_exec <command> [key=value ...]
  local command="$1"; shift
  python3 - "$ws/input.json" "$port" "$user" "$command" "$@" <<'PY'
import json, sys
path, port, user, command = sys.argv[1:5]
doc = {"host": "127.0.0.1", "port": port, "user": user, "command": command,
       "keyFile": "deploy_key", "knownHostsFile": "known_hosts"}
for pair in sys.argv[5:]:
    key, _, value = pair.partition("=")
    doc[key] = value
json.dump(doc, open(path, "w"))
PY
  run "$ws" >"$ws/atom.log" 2>&1
  echo "$ws"
}

# --- a plain command, and its output reaching the build log
run_exec 'echo hello-from-remote' >/dev/null
status "$ws" | grep -q success || fail "simple command: $(cat "$ws/output.json")"
grep -q "hello-from-remote" "$ws/atom.log" || fail "the remote output must be in the log"
[ "$(output "$ws" exitCode)" = "0" ] || fail "exit code: $(cat "$ws/output.json")"

# --- quoting must survive: the script goes over stdin, not through an argument
run_exec 'X="a b  c"; echo "$X" | tr -s " " "_"; echo "it'\''s fine"' >/dev/null
status "$ws" | grep -q success || fail "quoting: $(cat "$ws/output.json")"
grep -q "a_b_c" "$ws/atom.log" || fail "spaces and quotes should be preserved verbatim"
grep -q "it's fine" "$ws/atom.log" || fail "a single quote must not break the script"

# --- multi-line scripts keep their structure, and the last exit code decides
run_exec 'set -e
for i in 1 2 3; do echo "step $i"; done
test -d /tmp' >/dev/null
status "$ws" | grep -q success || fail "multi-line: $(cat "$ws/output.json")"
[ "$(grep -c "step " "$ws/atom.log")" = "3" ] || fail "each line of the loop should have run"

# --- a non-zero exit fails the step and carries the output
run_exec 'echo "about to fail"; echo "the reason is X" >&2; exit 3' >/dev/null
status "$ws" | grep -q error || fail "exit 3 must fail the step"
grep -q "exited 3 (expected 0)" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"
grep -q "the reason is X" "$ws/output.json" || fail "stderr should be quoted in the failure"
[ "$(output "$ws" exitCode)" = "3" ] || fail "the exit code must still be exported"

# --- an expected non-zero code is not a failure
run_exec 'exit 7' expectedExitCode=7 >/dev/null
status "$ws" | grep -q success || fail "expectedExitCode=7 should pass: $(cat "$ws/output.json")"

# --- failOnError=false keeps the build green but reports the code
run_exec 'echo nope >&2; exit 9' failOnError=false >/dev/null
status "$ws" | grep -q success || fail "failOnError=false must not fail: $(cat "$ws/output.json")"
[ "$(output "$ws" exitCode)" = "9" ] || fail "exit code with failOnError=false: $(cat "$ws/output.json")"

# --- a timeout kills the command instead of hanging the build
start=$(date +%s)
run_exec 'sleep 30' timeoutSeconds=5 >/dev/null
elapsed=$(( $(date +%s) - start ))
status "$ws" | grep -q error || fail "a timeout must fail"
grep -q "timed out after 5s" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"
[ "$elapsed" -lt 25 ] || fail "the run should have stopped near the timeout, took ${elapsed}s"

# --- ssh itself failing (nothing listening) is reported as such
python3 - "$ws/input.json" <<'PY'
import json, sys
json.dump({"host": "127.0.0.1", "port": "1", "user": "nobody", "command": "true",
           "keyFile": "deploy_key"}, open(sys.argv[1], "w"))
PY
run "$ws" >"$ws/atom.log" 2>&1 && fail "a dead port must fail"
grep -q "exited 255" "$ws/output.json" || fail "an ssh-level failure should say so: $(cat "$ws/output.json")"

# --- bad input
run_exec '' >/dev/null
status "$ws" | grep -q error || fail "an empty command must be refused"
grep -q "command is required" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"

echo "smoke ok: real sshd, quoting, multi-line, exit codes, expectedExitCode, failOnError, timeout, ssh failure, bad input"
