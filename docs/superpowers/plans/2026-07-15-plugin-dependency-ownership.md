# Plugin Dependency Ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the host declare only host-owned runtime libraries while keeping same-repository version management centralized and every official plugin Worker self-contained.

**Architecture:** The root parent POM remains the repository's version catalog. The host and each Worker select dependencies independently from that catalog; Workers package their runtime classpaths with Maven Shade and never rely on the host fat JAR. A small repository check locks down this ownership boundary before the Maven build verifies executable artifacts.

**Tech Stack:** Maven reactor, Maven Shade Plugin, Bash, Java 21, JSON-RPC Worker JARs

## Global Constraints

- Root `dependencyManagement` remains the single version source for all in-repository modules.
- External plugins own or import their own dependency versions and package their Worker runtime dependencies.
- `FengYu/pom.xml` contains no dependency solely because an official plugin needs it.
- Official Workers must not use `provided` for classes expected from the host process.
- Do not change plugin behavior, JSON-RPC, manifest schema, UI, permissions, or package layout.
- Preserve all unrelated dirty-worktree changes and stage only files from this plan.

---

## File Structure

- Create `scripts/check-plugin-dependency-boundaries.sh`: fast static regression check for POM ownership rules.
- Create `.mvn/maven.config`: supply the default CI-friendly `revision` before child parent resolution.
- Modify `pom.xml`: clarify that root version management is repository-local build policy, not a shared runtime classpath.
- Modify all child `pom.xml` files: resolve the current source parent through `${revision}` instead of a stale installed parent.
- Modify `FengYu/pom.xml`: remove unused plugin/legacy implementation libraries and correct API/SDK ownership comments.
- Modify `OfficialPlugins/plugin-markdown/pom.xml`: remove unused host-provided declarations so the Worker depends only on classes it uses.
- Modify `OfficialPlugins/plugin-excel/pom.xml`: document compile-scoped dependencies as Worker-owned and shaded.
- Modify `OfficialPlugins/plugin-email/pom.xml`: consume repository-managed versions instead of repeating local pins.

### Task 1: Add a Failing Dependency-Boundary Check

**Files:**
- Create: `scripts/check-plugin-dependency-boundaries.sh`

**Interfaces:**
- Consumes: repository POM files at fixed paths relative to the script.
- Produces: exit code `0` when ownership rules hold; nonzero with one diagnostic per violation.

- [ ] **Step 1: Add the executable boundary check**

```bash
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
```

- [ ] **Step 2: Make the check executable**

Run:

```bash
chmod +x scripts/check-plugin-dependency-boundaries.sh
```

- [ ] **Step 3: Run the check and verify RED**

Run:

```bash
scripts/check-plugin-dependency-boundaries.sh
```

Expected: exit `1`, reporting unwanted host dependencies, unused Markdown dependencies, stale host-provided comments, and locally repeated official-plugin versions.

- [ ] **Step 4: Commit the failing check**

```bash
git add scripts/check-plugin-dependency-boundaries.sh
git commit -m "🧪 test: enforce plugin dependency boundaries"
```

### Task 2: Apply Maven Dependency Ownership

**Files:**
- Modify: `pom.xml:38-56`
- Modify: `FengYu/pom.xml:67-93,166-182`
- Modify: `OfficialPlugins/plugin-markdown/pom.xml:22-46`
- Modify: `OfficialPlugins/plugin-excel/pom.xml:21-55`
- Modify: `OfficialPlugins/plugin-email/pom.xml:7-18`

**Interfaces:**
- Consumes: versions managed by the root parent POM.
- Produces: a host classpath without Fesod, PDFBox, POI, or Playwright; centrally managed official-plugin versions; shaded Worker dependency sets that do not rely on the host runtime.

- [ ] **Step 1: Clarify root version-management ownership**

Replace the root dependency-version comment with:

```xml
<!-- Repository-wide build versions consumed by host, SDK/API, and same-repository
     official plugin modules. dependencyManagement selects build versions only; it
     does not mean the FengYu host provides these libraries to isolated Workers. -->
```

Keep all existing version properties and managed dependency entries unchanged.

Add repository version properties for dependencies currently pinned only inside the
Excel Worker:

```xml
<spring-framework.version>7.0.8</spring-framework.version>
<jackson.version>2.21.4</jackson.version>
```

Add managed entries for the same-repository Workers:

```xml
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-context</artifactId>
    <version>${spring-framework.version}</version>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-model</artifactId>
    <version>${spring-ai.version}</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>${jackson.version}</version>
</dependency>
```

Configure Maven's CI-friendly version before model resolution:

```text
-Drevision=4.0.0-SNAPSHOT
```

Store that line in `.mvn/maven.config`, and change every direct child parent version
from `4.0.0-SNAPSHOT` to `${revision}`. This is required because a literal child
version does not match the root POM's raw `${revision}` version during relative-parent
validation; without it Maven can silently use an older parent from the local repository.

- [ ] **Step 2: Remove plugin-only and unused libraries from the host**

Delete these complete dependency blocks from `FengYu/pom.xml`:

```xml
<dependency>
    <groupId>org.apache.fesod</groupId>
    <artifactId>fesod-sheet</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
</dependency>
<dependency>
    <groupId>com.microsoft.playwright</groupId>
    <artifactId>playwright</artifactId>
</dependency>
```

Delete their now-orphaned section comments. Preserve CommonMark, Gson, Simple Java Mail, and Angus Mail because host production code uses them.

- [ ] **Step 3: Correct host API and SDK comments**

Use comments that describe host-owned usage rather than plugin class loading:

```xml
<!-- Shared API types used directly by the host AI, logging, and theme code. -->
```

```xml
<!-- Worker protocol/environment contracts used by the host runtime manager. -->
```

- [ ] **Step 4: Remove unused Markdown declarations**

Delete the complete `FengYu-Api` and `spring-context` dependency blocks and their stale comments from `OfficialPlugins/plugin-markdown/pom.xml`. Leave `fengyu-plugin-sdk`, CommonMark, Gson, and JUnit declarations intact; the first three remain compile-scoped and are included by Shade.

- [ ] **Step 5: Correct Excel Worker dependency comments**

Replace the Spring AI comment with:

```xml
<!-- Spring AI @Tool annotations referenced by Worker classes. Kept compile-scoped so
     the isolated shaded Worker owns its runtime classpath. -->
```

Replace the Jackson comment with:

```xml
<!-- Jackson ObjectMapper used directly by ToolJson. The isolated Worker packages the
     pinned runtime version instead of relying on the host's Spring AI transitives. -->
```

Do not add `provided` scope to FengYu API, Spring Context, Spring AI, or Jackson.

Remove dependency-level `<version>` elements for `fengyu-plugin-sdk`, `FengYu-Api`,
`spring-context`, `spring-ai-model`, and `jackson-databind` from the Excel POM so all
of them consume root management.

- [ ] **Step 6: Remove repeated official Email Worker versions**

Remove dependency-level `<version>` elements for `fengyu-plugin-sdk`, `mybatis`, and
`greenmail-junit5` from `OfficialPlugins/plugin-email/pom.xml`. Their versions already
exist in root `dependencyManagement`; preserve GreenMail's `<scope>test</scope>`.

Also remove the explicit `fengyu-plugin-sdk` version from the Markdown POM because the
root already manages that internal module.

- [ ] **Step 7: Run the boundary check and verify GREEN**

Run:

```bash
scripts/check-plugin-dependency-boundaries.sh
```

Expected: exit `0` and `Plugin dependency boundaries verified`.

- [ ] **Step 8: Inspect the focused diff**

Run:

```bash
git diff --check -- pom.xml FengYu/pom.xml OfficialPlugins/plugin-markdown/pom.xml OfficialPlugins/plugin-excel/pom.xml OfficialPlugins/plugin-email/pom.xml scripts/check-plugin-dependency-boundaries.sh
git diff -- pom.xml FengYu/pom.xml OfficialPlugins/plugin-markdown/pom.xml OfficialPlugins/plugin-excel/pom.xml OfficialPlugins/plugin-email/pom.xml scripts/check-plugin-dependency-boundaries.sh
```

Expected: no whitespace errors; only the planned comments, dependency removals, and boundary check appear.

- [ ] **Step 9: Commit the Maven ownership changes**

```bash
git add pom.xml FengYu/pom.xml OfficialPlugins/plugin-markdown/pom.xml OfficialPlugins/plugin-excel/pom.xml OfficialPlugins/plugin-email/pom.xml
git commit -m "♻️ refactor: isolate plugin runtime dependencies"
```

### Task 3: Verify Reactor and Worker Runtime Independence

**Files:**
- Verify: `pom.xml`
- Verify: `FengYu/pom.xml`
- Verify: `OfficialPlugins/plugin-markdown/target/markdown-worker.jar`
- Verify: `OfficialPlugins/plugin-excel/target/excel-worker.jar`
- Verify: `OfficialPlugins/plugin-email/target/email-worker.jar`

**Interfaces:**
- Consumes: modified Maven dependency declarations and existing Shade configurations.
- Produces: passing module tests, executable shaded Workers, and evidence that representative dependency classes live inside the Worker JARs.

- [ ] **Step 1: Set the repository-supported Maven executable**

```bash
MVN="$HOME/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
test -x "$MVN"
```

Expected: exit `0`.

- [ ] **Step 2: Run affected module tests and package Workers**

```bash
"$MVN" -f pom.xml \
  -pl FengYu,OfficialPlugins/plugin-markdown,OfficialPlugins/plugin-excel,OfficialPlugins/plugin-email \
  -am clean package
```

Expected: Maven reactor `BUILD SUCCESS` with no test failures.

- [ ] **Step 3: Verify the host resolved dependency tree excludes removed libraries**

```bash
"$MVN" -f pom.xml -pl FengYu dependency:tree \
  -Dincludes=org.apache.fesod:fesod-sheet,org.apache.pdfbox:pdfbox,org.apache.poi:poi-ooxml,com.microsoft.playwright:playwright \
  -DoutputFile=target/plugin-only-dependencies.txt
test ! -s FengYu/target/plugin-only-dependencies.txt
```

Expected: the output file is empty and `test ! -s` exits `0`.

- [ ] **Step 4: Inspect representative Worker runtime classes**

```bash
jar tf OfficialPlugins/plugin-markdown/target/markdown-worker.jar | grep -qx 'org/commonmark/parser/Parser.class'
jar tf OfficialPlugins/plugin-markdown/target/markdown-worker.jar | grep -qx 'fan/summer/fengyu/sdk/JsonRpcWorker.class'
jar tf OfficialPlugins/plugin-excel/target/excel-worker.jar | grep -qx 'org/apache/poi/xssf/usermodel/XSSFWorkbook.class'
jar tf OfficialPlugins/plugin-excel/target/excel-worker.jar | grep -qx 'fan/summer/fengyu/api/ai/FengYuTool.class'
jar tf OfficialPlugins/plugin-excel/target/excel-worker.jar | grep -qx 'org/springframework/ai/tool/annotation/Tool.class'
jar tf OfficialPlugins/plugin-excel/target/excel-worker.jar | grep -qx 'com/fasterxml/jackson/databind/ObjectMapper.class'
jar tf OfficialPlugins/plugin-email/target/email-worker.jar | grep -qx 'org/simplejavamail/api/email/Email.class'
```

Expected: every command exits `0`, proving the child processes do not need host classes.

- [ ] **Step 5: Exercise the Markdown Worker without the host classpath**

```bash
output=$(printf '%s\n' '{"jsonrpc":"2.0","id":"dependency-check","method":"render","params":{"markdown":"# isolated"}}' \
  | java -jar OfficialPlugins/plugin-markdown/target/markdown-worker.jar)
grep -Fq '"id":"dependency-check"' <<< "$output"
grep -Fq '\u003ch1\u003eisolated\u003c/h1\u003e' <<< "$output"
```

Expected: exit `0`.

- [ ] **Step 6: Run the official package build checks**

```bash
PATH="$(dirname "$MVN"):$PATH" OfficialPlugins/build-packages.sh
```

Expected: exit `0` and `Packages written to .../OfficialPlugins/target/packages`.

- [ ] **Step 7: Run final ownership and diff checks**

```bash
scripts/check-plugin-dependency-boundaries.sh
git diff --check HEAD^
git status --short
```

Expected: the boundary check passes; no whitespace errors; `git status` shows only pre-existing unrelated user changes and any intentionally uncommitted files.
