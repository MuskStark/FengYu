#!/usr/bin/env bash
# Infinia portable Web launcher. Requires Java 21 on PATH (or JAVA_HOME).
# The backend binds loopback only (127.0.0.1); pass extra HeadlessLauncher args through, e.g.
#   ./run.sh --port=8080 --token=secret
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "$JAVA" >/dev/null 2>&1 || { echo "Java 21 is required" >&2; exit 1; }
MAJOR="$($JAVA -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
[ "${MAJOR:-0}" -ge 21 ] || { echo "Java 21 is required" >&2; exit 1; }
exec "$JAVA" -Dfengyu.plugins.official-directory="$ROOT/plugins" -jar "$ROOT/Infinia.jar" "$@"
