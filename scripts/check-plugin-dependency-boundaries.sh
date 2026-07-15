#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
failures=0

require_text() {
  local file="$1" text="$2" message="$3"
  if ! grep -Fq "$text" "$file"; then
    echo "FAIL: $message" >&2
    failures=$((failures + 1))
  fi
}

reject_text() {
  local file="$1" text="$2" message="$3"
  if grep -Fq "$text" "$file"; then
    echo "FAIL: $message" >&2
    failures=$((failures + 1))
  fi
}

for artifact in fesod-sheet commonmark pdfbox poi-ooxml playwright spring-context spring-ai-model jackson-databind; do
  require_text "$ROOT/pom.xml" "<artifactId>$artifact</artifactId>" \
    "root dependencyManagement must continue to manage $artifact"
done

for artifact in fesod-sheet pdfbox poi-ooxml playwright; do
  reject_text "$ROOT/FengYu/pom.xml" "<artifactId>$artifact</artifactId>" \
    "host must not declare plugin-only or unused library $artifact"
done

reject_text "$ROOT/OfficialPlugins/plugin-markdown/pom.xml" \
  '<artifactId>FengYu-Api</artifactId>' \
  'Markdown Worker must not declare unused FengYu-Api'
reject_text "$ROOT/OfficialPlugins/plugin-markdown/pom.xml" \
  '<artifactId>spring-context</artifactId>' \
  'Markdown Worker must not declare unused Spring Context'
reject_text "$ROOT/OfficialPlugins/plugin-markdown/pom.xml" \
  '<scope>provided</scope>' \
  'Markdown Worker must not rely on host-provided runtime classes'
reject_text "$ROOT/OfficialPlugins/plugin-excel/pom.xml" \
  'provided by app at runtime' \
  'Excel Worker comments must not claim host classpath sharing'
reject_text "$ROOT/OfficialPlugins/plugin-excel/pom.xml" \
  '<scope>provided</scope>' \
  'Excel Worker runtime dependencies must be shaded, not provided'

for version in 7.0.8 2.0.0 2.21.4; do
  reject_text "$ROOT/OfficialPlugins/plugin-excel/pom.xml" "<version>$version</version>" \
    "Excel Worker must consume repository-managed version $version"
done

for version in 3.5.19 2.1.3; do
  reject_text "$ROOT/OfficialPlugins/plugin-email/pom.xml" "<version>$version</version>" \
    "Email Worker must consume repository-managed version $version"
done

if (( failures > 0 )); then
  exit 1
fi

echo "Plugin dependency boundaries verified"
