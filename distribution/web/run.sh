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
# 若用户未显式传 --token,生成一个随机 per-launch token 并传入,避免默认认证关闭。
# 用户传 --token=<t> 时此处不覆盖(下面的 case 检测)。
TOKEN_ARGS=()
case " $* " in *" --token"*) ;; *" --token="*) ;; *)
  GEN_TOKEN="zf-$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')-$$"
  TOKEN_ARGS=(--token="$GEN_TOKEN")
  echo "Generated per-launch token (pass --token=<t> to override): $GEN_TOKEN" >&2
;; esac

exec "$JAVA" \
  -Dfengyu.runtime.dir="$ROOT/data" \
  -Dfengyu.plugins.official-directory="$ROOT/plugins" \
  -Dfengyu.update.portable=true \
  -jar "$ROOT/Infinia.jar" "${TOKEN_ARGS[@]}" "$@"
