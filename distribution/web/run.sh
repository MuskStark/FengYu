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
# 若用户未显式传 --token=<t>,生成一个随机 per-launch token 并传入,避免默认认证关闭。
# token 参数必须精确匹配且值非空；拼写错误不能静默关闭认证。
HAS_TOKEN=0
for arg in "$@"; do
  case "$arg" in
    --token=*)
      token_value="${arg#--token=}"
      [ -n "${token_value//[[:space:]]/}" ] || {
        echo "Invalid --token argument: use --token=<non-empty value>" >&2
        exit 2
      }
      [ "$HAS_TOKEN" -eq 0 ] || {
        echo "Invalid arguments: --token may be supplied only once" >&2
        exit 2
      }
      HAS_TOKEN=1
      ;;
    --token*)
      echo "Invalid token argument '$arg': use --token=<non-empty value>" >&2
      exit 2
      ;;
  esac
done
if [ "$HAS_TOKEN" -eq 0 ]; then
  GEN_TOKEN="zf-$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')-$$"
  echo "Generated per-launch token (pass --token=<t> to override): $GEN_TOKEN" >&2
fi

if [ "$HAS_TOKEN" -eq 0 ]; then
  exec "$JAVA" \
    -Dfengyu.runtime.dir="$ROOT/data" \
    -Dfengyu.plugins.official-directory="$ROOT/plugins" \
    -Dfengyu.update.portable=true \
    -jar "$ROOT/Infinia.jar" --token="$GEN_TOKEN" "$@"
else
  exec "$JAVA" \
    -Dfengyu.runtime.dir="$ROOT/data" \
    -Dfengyu.plugins.official-directory="$ROOT/plugins" \
    -Dfengyu.update.portable=true \
    -jar "$ROOT/Infinia.jar" "$@"
fi
