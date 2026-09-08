#!/usr/bin/env bash
# Exercise the real entrypoint with local command substitutes, forcing exit between kill -0 and kill -TERM.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf -- "$fixture"' EXIT
mkdir -p "$fixture/tools" "$fixture/bin" "$fixture/workspace"
cp "$root/tools/test-mac-hosts.sh" "$fixture/tools/"
printf '#!/bin/sh\nexit 0\n' >"$fixture/mvnw"
printf '#!/bin/sh\nexit 0\n' >"$fixture/bin/java"
chmod +x "$fixture/mvnw" "$fixture/bin/java"
cat >"$fixture/commands.sh" <<'COMMANDS'
# No real JVM, Maven build or network call is needed for the cleanup boundary.
mktemp() { printf '%s\n' "$TEST_WORKSPACE"; }
grep() { printf '%s\n' 'http://127.0.0.1:4566'; }
kill() {
  case "$1" in
    -TERM) child_exited=true; return 1 ;;
    -0) [[ "${child_exited:-false}" == false ]] ;;
    *) printf 'Unexpected kill argument: %s\n' "$1" >&2; return 2 ;;
  esac
}
COMMANDS
if ! PATH="$fixture/bin:$PATH" BASH_ENV="$fixture/commands.sh" TEST_WORKSPACE="$fixture/workspace" \
  bash "$fixture/tools/test-mac-hosts.sh"; then
  printf '%s\n' 'Cleanup regression: an already-exited child must not abort workspace cleanup' >&2
  exit 1
fi
if [[ -e "$fixture/workspace" ]]; then
  printf '%s\n' 'Cleanup regression: test workspace was not removed' >&2
  exit 1
fi
printf '%s\n' 'Cleanup regression passed: exited child does not prevent workspace removal'
