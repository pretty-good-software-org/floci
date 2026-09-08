#!/usr/bin/env bash
# Runs the affected suite, then SDK tests against an owned, disposable emulator. Never uses AWS credentials or Docker.
set -euo pipefail
cd "$(dirname "$0")/.."

# Scrub inherited cloud authentication in this subprocess; the caller's environment is unchanged.
for name in $(compgen -v AWS_); do unset "$name"; done
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_EC2_METADATA_DISABLED=true

affected_tests='Ec2*Test,ContainerNetworkReachabilityTest,FlowLogServiceTest,Ipv4CidrsTest,IamActionRegistryTest,ResourceArnBuilderTest,IamConditionContextResolverTest,IamEnforcementFilterTest,IamEnforcementFilterUnitTest'
./mvnw package "-Dtest=${affected_tests}" -Dfloci.services.ec2.mock=true

workspace="$(mktemp -d)"
: >"$workspace/server.log"
server_pid=''
cleanup() {
  local status=$?
  trap - EXIT
  if [[ -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then
    kill -TERM "$server_pid"
    local server_status=0
    wait "$server_pid" || server_status=$?
    # SIGTERM is the expected shutdown signal for the owned JVM.
    if [[ "$server_status" != 0 && "$server_status" != 143 ]]; then
      printf 'Stop test emulator: unexpected exit %s\n' "$server_status" >&2
      status=1
    fi
  fi
  if [[ "$status" != 0 ]]; then
    tail -60 "$workspace/server.log" >&2
  fi
  if ! rm -rf -- "$workspace"; then
    printf '%s\n' 'Remove test workspace: cleanup failed' >&2
    status=1
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

java -Dquarkus.http.host=127.0.0.1 -Dquarkus.http.port=0 -Dfloci.port=0 \
  -Dfloci.storage.persistent-path="$workspace/state" -Dfloci.storage.mode=memory \
  -Dfloci.services.ec2.mock=true -Dfloci.services.ec2.imds-port=0 \
  -Dfloci.services.ec2.reconcile-containers-on-startup=false \
  -Dfloci.docker.docker-host="unix://$workspace/no-docker.sock" \
  -Dfloci.docker.resource-namespace="mac-host-test-$$" -Dfloci.tls.enabled=false \
  -jar target/quarkus-app/quarkus-run.jar >"$workspace/server.log" 2>&1 &
server_pid=$!

startup_attempts=60
for ((attempt=0; attempt<startup_attempts; attempt++)); do
  # No matching startup line is expected until Quarkus finishes binding its HTTP listener.
  endpoint="$(grep 'Listening on:' "$workspace/server.log" | grep -Eo 'http://127\.0\.0\.1:[0-9]+' | tail -1 || true)"
  if [[ -n "$endpoint" ]]; then break; fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    printf '%s\n' 'Start test emulator: process exited before HTTP startup' >&2
    exit 1
  fi
  sleep 1
done
if [[ -z "$endpoint" ]]; then
  printf '%s\n' 'Start test emulator: HTTP startup timed out' >&2
  exit 1
fi
export FLOCI_ENDPOINT="$endpoint" FLOCI_TARGET=floci
./mvnw -f compatibility-tests/sdk-test-java/pom.xml test -Dtest=Ec2DedicatedHostTests
