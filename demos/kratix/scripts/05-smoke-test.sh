#!/usr/bin/env bash
set -euo pipefail

LOCAL_PORT="${LOCAL_PORT:-8080}"
PORT_FORWARD_LOG="${TMPDIR:-/tmp}/request-logger-port-forward.log"

printf '[platform-demo] opening port-forward to svc/request-logger on localhost:%s\n' "${LOCAL_PORT}"
kubectl -n demo port-forward svc/request-logger "${LOCAL_PORT}:8080" >"${PORT_FORWARD_LOG}" 2>&1 &
PF_PID="$!"
trap 'kill "${PF_PID}" >/dev/null 2>&1 || true' EXIT

for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${LOCAL_PORT}/ready" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

RESPONSE="$(curl -fsS "http://127.0.0.1:${LOCAL_PORT}/demo")"
printf '%s\n' "${RESPONSE}"

if ! printf '%s' "${RESPONSE}" | grep -q '"todosApi":"ok"'; then
  printf '[platform-demo] todos API smoke check failed\n' >&2
  exit 1
fi
if ! printf '%s' "${RESPONSE}" | grep -q '"cache":"ok"'; then
  printf '[platform-demo] cache smoke check failed\n' >&2
  exit 1
fi

printf '[platform-demo] smoke test passed\n'
