#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/docs/plugins"
DST="$ROOT/.claude-plugin/plugin/standards"
mkdir -p "$DST"

# 1) 快照标准文档
cp "$SRC"/entry-point.md "$SRC"/spi.md "$SRC"/pitfalls.md \
   "$SRC"/plugin-host.md "$SRC"/ui.md "$SRC"/i18n.md "$SRC"/database.md "$DST"/
# ai-tools 文档若存在则一并快照(AiTool 契约来自 CLAUDE.md,若无独立 md 则跳过)
[ -f "$SRC/ai-tools.md" ] && cp "$SRC/ai-tools.md" "$DST"/ || true

# 2) 版本戳:从 API pom 读 <version>
VER="$(grep -m1 -oE '<version>[^<]+</version>' "$ROOT/SwissKitJ-Api/pom.xml" | sed -E 's/<[^>]+>//g')"
printf '%s\n' "$VER" > "$DST/VERSION"

# 3) 更新 plugin.json version 与 pom 一致
PJ="$ROOT/.claude-plugin/plugin/plugin.json"
sed -i.bak -E "s/(\"version\"[[:space:]]*:[[:space:]]*\")[^\"]+(\")/\1$VER\2/" "$PJ" && rm -f "$PJ.bak"

echo "Synced standards @ API $VER"
