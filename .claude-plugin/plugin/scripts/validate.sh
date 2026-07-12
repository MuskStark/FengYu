#!/usr/bin/env bash
# Mechanical compliance checker for swisskitj-plugin-kit plugins.
# Implements rules M1-M12 from standards/checklist.md.
# Usage: validate.sh <plugin-dir>
# Exit code: 0 = all mechanical rules pass; non-zero = at least one FAIL printed.
set -uo pipefail
P="${1:?usage: validate.sh <plugin-dir>}"
SRC="$P/src/main/java"; RES="$P/src/main/resources"; POM="$P/pom.xml"
rc=0
fail(){ echo "FAIL $1: $2"; rc=1; }
ok(){ :; }

# M1: SPI file exists
SPI="$RES/META-INF/services/fan.summer.fengyu.api.SwissKitJPlugin"
[ -f "$SPI" ] || fail M1 "missing SPI file"

# M2: SPI content = entry class FQN, and that .java exists
if [ -f "$SPI" ]; then
  fqn="$(grep -m1 -v '^\s*$' "$SPI" | tr -d '\r')"
  path="$SRC/$(echo "$fqn" | tr '.' '/').java"
  [ -f "$path" ] || fail M2 "SPI class not found: $fqn"
fi

# M3: SwissKitJ-Api dependency scope is provided
# Must check scope *within the SwissKitJ-Api <dependency> block* specifically —
# a pom-wide grep for "<scope>provided</scope>" would false-pass as long as any
# other dependency (e.g. javafx, lombok) happens to be provided too.
api_block="$(awk '/<dependency>/{blk=""; capture=1} capture{blk=blk $0 ORS} /<\/dependency>/{if (capture && blk ~ /<artifactId>SwissKitJ-Api<\/artifactId>/) print blk; capture=0}' "$POM" 2>/dev/null)"
if [ -z "$api_block" ]; then
  fail M3 "SwissKitJ-Api dependency not found"
else
  echo "$api_block" | grep -qsE '<scope>provided</scope>' || fail M3 "SwissKitJ-Api must be provided"
fi

# M4: no .glass- CSS reference (source + resources)
grep -rqs 'glass-' "$SRC" "$RES" 2>/dev/null && fail M4 "'.glass-*' found; use .sk-*"

# M5: no setPrefWidth(Double.MAX_VALUE)
grep -rqs 'setPrefWidth(Double.MAX_VALUE)' "$SRC" 2>/dev/null && fail M5 "setPrefWidth(MAX_VALUE) banned"

# M6: no maxWidthProperty().bind(widthProperty()) self/circular binding
# Checked two ways: (a) line-oriented grep for the single-line form, and
# (b) per-file whitespace-collapse for the multi-line/wrapped form (CLAUDE.md §3),
# e.g. `desc.maxWidthProperty()\n    .bind(\n        widthProperty().subtract(48));`
m6_hit=0
grep -rqsE 'maxWidthProperty\(\)\.bind\(\s*widthProperty\(\)' "$SRC" 2>/dev/null && m6_hit=1
if [ "$m6_hit" -eq 0 ] && [ -d "$SRC" ]; then
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    tr -s ' \t\n\r' ' ' < "$f" | grep -qE 'maxWidthProperty\(\) *\. *bind\( *widthProperty\(\)' && { m6_hit=1; break; }
  done < <(find "$SRC" -name '*.java' 2>/dev/null)
fi
[ "$m6_hit" -eq 1 ] && fail M6 "self width bind banned"

# M7: plugin getId() is reverse-domain (at least two dot-separated segments)
# getId() and its return statement are usually on separate lines, so grab the
# getId() line plus one line of trailing context (-A1) rather than assuming
# a single-line "getId() { return "..." }" pattern.
if [ -f "$SPI" ]; then
  id_block="$(grep -rhA1 'getId()' "$SRC" 2>/dev/null | head -2)"
  echo "$id_block" | grep -qE 'return\s*"[a-zA-Z0-9_-]+(\.[a-zA-Z0-9_-]+)+"' || fail M7 "getId not reverse-domain"
fi

# M8: pom configures shade ServicesResourceTransformer
grep -qs 'ServicesResourceTransformer' "$POM" || fail M8 "shade ServicesResourceTransformer missing"

# M9: i18n/messages.properties exists
[ -f "$RES/i18n/messages.properties" ] || fail M9 "i18n/messages.properties missing"

# M10: i18n bundle registered in createView/init
grep -rqsE 'registerPluginBundle|i18n\(\)\.registerBundle' "$SRC" 2>/dev/null || fail M10 "i18n bundle not registered"

# M11: DevLauncher.java has zero javafx references (import or FQN)
dl="$(grep -rl 'class DevLauncher' "$SRC" 2>/dev/null | head -1)"
[ -n "$dl" ] && grep -qs 'javafx' "$dl" && fail M11 "DevLauncher must contain zero JavaFX references (move JavaFX into DevApp)"

# M12: pom has swisskit.api.version property DECLARATION (not just a ${...} usage reference)
grep -qsE '<swisskit\.api\.version>' "$POM" || fail M12 "swisskit.api.version property missing"

[ "$rc" -eq 0 ] && echo "VALIDATE OK: $P"
exit $rc
