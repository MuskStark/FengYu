#!/usr/bin/env bash
# Train the JDK 25 AOT cache for the with-JRE build variant and stage it INSIDE the
# jlink runtime directory (<jre-dir>/FengYu.aot + FengYu.aot.meta.json).
#
# An AOT cache is only usable by the exact JVM family that dumped it, and only when the
# runtime classpath STRING matches the training one byte for byte. That drives both
# placement decisions here:
#   - the cache lives in <jre-dir>, next to the JVM it is bound to, and ships with the
#     wholesale resources/jre extraResource (no electron-builder config change needed);
#   - the training launch runs with cwd=<jar-dir> and the RELATIVE classpath
#     `-cp FengYu.jar`, exactly the form spawn.ts uses when it finds a valid cache —
#     install location can differ arbitrarily, the relative string cannot.
#
# Training boots the real APP-mode context once against a throwaway seeded H2 database
# (the e2e-smoke recipe: never the user's DB, never SETUP mode — a SETUP-trained cache
# would miss the entire JPA/AI/plugin stack this exists to speed up). The launch carries
# -Dfengyu.aot.training-exit=true so the JVM exits on its own after ready and the
# -XX:AOTCacheOutput flag writes the archive on the way out; no signals are involved
# (Windows has no graceful one, and a hard kill skips the dump).
#
# Usage (run AFTER build-jre.sh, on the same runner that runs electron-builder):
#   train-aot-cache.sh <path/to/FengYu.jar> <jre-dir> <app-version>
#
# Exits non-zero on any failure; CI must treat that as fatal so a release never ships a
# stale or partial cache silently.
set -euo pipefail

JAR="${1:?usage: train-aot-cache.sh <path/to/FengYu.jar> <jre-dir> <app-version>}"
JRE_DIR="${2:?usage: train-aot-cache.sh <path/to/FengYu.jar> <jre-dir> <app-version>}"
APP_VERSION="${3:?usage: train-aot-cache.sh <path/to/FengYu.jar> <jre-dir> <app-version>}"

# Absolutize BEFORE anything cd's: callers pass repo-root-relative paths (the
# workflows use no working-directory override), while the training launch below
# runs with cwd=<jar-dir> — every later $JAR/$JRE_DIR/$JAVA_BIN use must survive
# that cd.
JAR_DIR="$(cd "$(dirname "$JAR")" && pwd)"
JAR_NAME="$(basename "$JAR")"
JAR="$JAR_DIR/$JAR_NAME"
JRE_DIR="$(cd "$JRE_DIR" && pwd)"

# Resolve the bundled java binary for this platform (java on POSIX, java.exe on Windows).
if [ -x "$JRE_DIR/bin/java" ]; then
  JAVA_BIN="$JRE_DIR/bin/java"
elif [ -x "$JRE_DIR/bin/java.exe" ]; then
  JAVA_BIN="$JRE_DIR/bin/java.exe"
else
  echo "[train-aot] FAIL: no java binary under $JRE_DIR/bin" >&2
  exit 1
fi

[ -f "$JAR" ] || { echo "[train-aot] FAIL: jar not found at $JAR" >&2; exit 1; }

# The runtime's own release file is the JVM identity the meta must record — the desktop
# compares it against the shipped <resources>/jre/release at every launch.
JRE_VERSION=$(sed -n 's/^JAVA_VERSION="\([^"]*\)".*/\1/p' "$JRE_DIR/release" | head -1)
[ -n "$JRE_VERSION" ] || { echo "[train-aot] FAIL: cannot read JAVA_VERSION from $JRE_DIR/release" >&2; exit 1; }

# On a Windows runner this script runs under git-bash, whose POSIX-style absolute paths
# a native java.exe cannot read (and MSYS automatic argument conversion must not be
# relied on). Convert every absolute path handed to the JVM explicitly; POSIX platforms
# pass through unchanged.
native_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
WORK_N=$(native_path "$WORK")

# ── Throwaway APP-mode runtime root (never the user's state) ─────────────────────────
DB_FILE="$WORK/.fengyu/database/fengyu"
mkdir -p "$WORK/.fengyu/config" "$(dirname "$DB_FILE")"
cat > "$WORK/.fengyu/config/datasource.properties" <<EOF
db.type=h2
db.url=jdbc:h2:file:${WORK_N}/.fengyu/database/fengyu
db.driver=org.h2.Driver
db.dialect=org.hibernate.dialect.H2Dialect
db.username=sa
db.admin.username=sa
db.file.path=${WORK_N}/.fengyu/database/fengyu
EOF

# Create the empty embedded database the way the SETUP wizard does. The H2 TCP server
# refuses to create databases, so without this the startup probe would find no DB and
# boot the minimal SETUP context instead of the APP one this training needs.
echo "SELECT 1;" | "$JAVA_BIN" -cp "$JAR" org.h2.tools.Shell \
  -url "jdbc:h2:file:$WORK_N/.fengyu/database/fengyu" -user sa -password "" >/dev/null 2>&1 \
  || { echo "[train-aot] FAIL: could not create the throwaway H2 database" >&2; exit 1; }

echo "[train-aot] training with $JAVA_BIN (JAVA_VERSION=$JRE_VERSION), classpath -cp $JAR_NAME from $JAR_DIR"

# ── The training launch ───────────────────────────────────────────────────────────────
# port 0 = OS-assigned (never collides with CI services); the store base points at a
# closed local port so the store client fails fast instead of hanging on a network the
# runner may not have; training-exit makes the JVM quit itself after ready.
cd "$JAR_DIR"
"$JAVA_BIN" \
  "-Dfengyu.runtime.dir=$WORK_N/.fengyu" \
  "-Dfengyu.plugins.directory=$WORK_N/.fengyu/plugins" \
  "-Dfengyu.plugins.data-directory=$WORK_N/.fengyu/plugin-data" \
  "-Dfengyu.store.api-base=http://127.0.0.1:9" \
  -Dfengyu.aot.training-exit=true \
  "-XX:AOTCacheOutput=$WORK_N/FengYu.aot" \
  -cp "$JAR_NAME" \
  fan.summer.fengyu.HeadlessLauncher --port=0 --token=aot-train \
  > "$WORK/train.log" 2>&1 &
TRAINER_PID=$!

# Wait for the self-exit (boot + 3s grace + dump write ≈ 10–30s; CI runners can be much
# slower cold). Last-resort hard kill on timeout — the validation below then fails the
# step instead of letting a partial cache through. `wait` is guarded because set -e
# would otherwise abort before the status is captured.
DEADLINE=$((SECONDS + 420))
while kill -0 "$TRAINER_PID" 2>/dev/null; do
  if [ "$SECONDS" -ge "$DEADLINE" ]; then
    kill -9 "$TRAINER_PID" 2>/dev/null || true
    echo "[train-aot] FAIL: training launch did not exit within 420s" >&2
    tail -30 "$WORK/train.log" >&2
    exit 1
  fi
  sleep 1
done
TRAIN_EXIT=0
wait "$TRAINER_PID" || TRAIN_EXIT=$?
if [ "$TRAIN_EXIT" -ne 0 ]; then
  echo "[train-aot] FAIL: training launch exited with $TRAIN_EXIT" >&2
  tail -30 "$WORK/train.log" >&2
  exit 1
fi

# ── Validate the dump before staging it ──────────────────────────────────────────────
# Mode gate: prove the FULL application context started, not the SETUP wizard one
# (both are launched by the same HeadlessLauncher main class, so the Spring "Started
# <main class>" line cannot discriminate). The APP context alone initializes JPA and
# runs Flyway — either line is therefore impossible in a SETUP fallback boot.
APP_MODE_MARK='Successfully validated [0-9]+ migrations|Initialized JPA EntityManagerFactory'
grep -Eq "$APP_MODE_MARK" "$WORK/train.log" || {
  echo "[train-aot] FAIL: training boot did not reach the APP context (SETUP fallback?)" >&2
  tail -30 "$WORK/train.log" >&2
  exit 1
}
[ -f "$WORK/FengYu.aot" ] || { echo "[train-aot] FAIL: no cache written at exit" >&2; exit 1; }
CACHE_BYTES=$(wc -c < "$WORK/FengYu.aot" | tr -d ' ')
if [ "$CACHE_BYTES" -lt 20971520 ]; then
  echo "[train-aot] FAIL: cache suspiciously small ($CACHE_BYTES bytes < 20 MiB)" >&2
  exit 1
fi

# ── Stage cache + identity meta next to the JVM ──────────────────────────────────────
JAR_BYTES=$(wc -c < "$JAR" | tr -d ' ')
mv "$WORK/FengYu.aot" "$JRE_DIR/FengYu.aot"
printf '{\n  "kind": "ci",\n  "appVersion": "%s",\n  "jvmVersion": "%s",\n  "jarBytes": %s\n}\n' \
  "$APP_VERSION" "$JRE_VERSION" "$JAR_BYTES" > "$JRE_DIR/FengYu.aot.meta.json"

echo "[train-aot] staged $JRE_DIR/FengYu.aot ($((CACHE_BYTES / 1048576)) MiB) + meta for v$APP_VERSION / JRE $JRE_VERSION"
