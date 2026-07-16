# Alpha Desktop and Web Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish `v4.0.0-alpha.1` as unsigned Windows, macOS, and Linux Tauri packages plus a portable loopback-only Web distribution.

**Architecture:** Build one Vue production bundle and embed it in the shaded Spring Boot JAR, which becomes the shared runtime for browser and desktop use. Stage that JAR and the official `.fyp` plugins as Tauri resources for native packaging, and assemble the same inputs into a self-hosted Web archive. A rewritten GitHub Actions workflow validates the tag, builds/tests shared inputs, packages every target, smoke-tests the Web archive, and creates a GitHub prerelease.

**Tech Stack:** Java 21, Spring Boot 4/Spring MVC, Maven Shade, Vue 3/Vite, Node 20 tests, Rust/Tauri 2, Bash/PowerShell, GitHub Actions.

---

## File Structure

- Create `scripts/resolve-release-version.mjs` and its Node test — validate release tags and emit full, numeric, and prerelease values.
- Create `frontend/build/release-version.mjs` and its Node test — select CI tag version over `package.json`.
- Modify `frontend/vite.config.ts` — inject the resolved release version.
- Modify `FengYu/pom.xml` — include `frontend/dist` as `static/` resources in the shaded JAR.
- Create `FengYu/src/main/java/fan/summer/fengyu/web/SpaForwardController.java` and its MVC test — forward known Vue routes to `index.html`.
- Modify `desktop/src-tauri/src/main.rs` and `tauri.conf.json` — use Tauri's resource directory for the staged JAR/plugins.
- Create `distribution/web/*` and Web packaging/smoke scripts — portable Java 21 distribution.
- Replace `.github/workflows/fengyu-release.yml` — current 4.0 desktop/Web release pipeline.
- Modify release-facing README, quickstart, desktop README, and changelog sections.

### Task 1: Release tag resolver

**Files:**
- Create: `scripts/resolve-release-version.mjs`
- Create: `scripts/resolve-release-version.test.mjs`

- [ ] **Step 1: Write the failing table-driven tests**

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveReleaseVersion } from './resolve-release-version.mjs'

for (const [tag, version, prerelease] of [
  ['v4.0.0', '4.0.0', false],
  ['v4.0.0-alpha.1', '4.0.0-alpha.1', true],
  ['v4.0.0-beta.2', '4.0.0-beta.2', true],
  ['v4.0.0-rc.3', '4.0.0-rc.3', true],
]) {
  test(`resolves ${tag}`, () => {
    assert.deepEqual(resolveReleaseVersion(tag), {
      tag, version, appVersion: '4.0.0', prerelease,
    })
  })
}

for (const tag of ['4.0.0', 'v4', 'v4.0.0-alpha', 'v4.0.0-preview.1']) {
  test(`rejects ${tag}`, () => assert.throws(() => resolveReleaseVersion(tag), /Invalid release tag/))
}
```

- [ ] **Step 2: Run the test and confirm the missing-module failure**

Run: `node --test scripts/resolve-release-version.test.mjs`

Expected: FAIL because `resolve-release-version.mjs` does not exist.

- [ ] **Step 3: Implement the resolver and GitHub output CLI**

```js
import { appendFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'

const TAG = /^v(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?$/

export function resolveReleaseVersion(tag) {
  const value = String(tag ?? '').trim()
  const match = TAG.exec(value)
  if (!match) throw new Error(`Invalid release tag: ${value}`)
  const appVersion = `${match[1]}.${match[2]}.${match[3]}`
  return { tag: value, version: value.slice(1), appVersion, prerelease: Boolean(match[4]) }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const result = resolveReleaseVersion(process.argv[2])
  if (!process.env.GITHUB_OUTPUT) throw new Error('GITHUB_OUTPUT is required')
  appendFileSync(process.env.GITHUB_OUTPUT,
    Object.entries(result).map(([key, value]) => `${key}=${value}\n`).join(''))
}
```

- [ ] **Step 4: Run resolver tests**

Run: `node --test scripts/resolve-release-version.test.mjs`

Expected: 8 passing tests and exit code 0.

- [ ] **Step 5: Commit the resolver**

```bash
git add scripts/resolve-release-version.mjs scripts/resolve-release-version.test.mjs
git commit -m "✨ feat(release): validate semantic release tags"
```

### Task 2: Release-aware frontend build

**Files:**
- Create: `frontend/build/release-version.mjs`
- Create: `frontend/test/release-version.test.mjs`
- Modify: `frontend/vite.config.ts`

- [ ] **Step 1: Write precedence tests**

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveFrontendVersion } from '../build/release-version.mjs'

test('uses package version outside release CI', () => {
  assert.equal(resolveFrontendVersion('4.0.0', {}), '4.0.0')
})
test('uses release version in CI', () => {
  assert.equal(resolveFrontendVersion('4.0.0', { FENGYU_RELEASE_VERSION: '4.0.0-alpha.1' }), '4.0.0-alpha.1')
})
test('rejects malformed release override', () => {
  assert.throws(() => resolveFrontendVersion('4.0.0', { FENGYU_RELEASE_VERSION: 'alpha' }), /Invalid/)
})
```

- [ ] **Step 2: Run the targeted test and confirm failure**

Run: `cd frontend && node --test test/release-version.test.mjs`

Expected: FAIL because `build/release-version.mjs` does not exist.

- [ ] **Step 3: Implement the pure selector**

```js
const VERSION = /^\d+\.\d+\.\d+(?:-(?:alpha|beta|rc)\.\d+)?$/
export function resolveFrontendVersion(packageVersion, env = process.env) {
  const value = env.FENGYU_RELEASE_VERSION || packageVersion
  if (!VERSION.test(value)) throw new Error(`Invalid frontend release version: ${value}`)
  return value
}
```

- [ ] **Step 4: Wire the selector into Vite**

Import `resolveFrontendVersion` from `./build/release-version.mjs`, rename the parsed JSON value to `packageVersion`, create `const appVersion = resolveFrontendVersion(packageVersion)`, and change `__APP_VERSION__` to `JSON.stringify(appVersion)`.

- [ ] **Step 5: Verify tests, type checking, and Alpha metadata**

```bash
cd frontend
npm test
npm run typecheck
FENGYU_RELEASE_VERSION=4.0.0-alpha.1 npm run build
rg -a "4.0.0-alpha.1" dist/assets
```

Expected: all commands pass and the built assets contain `4.0.0-alpha.1`.

- [ ] **Step 6: Commit frontend version handling**

```bash
git add frontend/build/release-version.mjs frontend/test/release-version.test.mjs frontend/vite.config.ts
git commit -m "✨ feat(frontend): inject release tag version"
```

### Task 3: Serve the Vue SPA from the backend JAR

**Files:**
- Modify: `FengYu/pom.xml`
- Create: `FengYu/src/main/java/fan/summer/fengyu/web/SpaForwardController.java`
- Create: `FengYu/src/test/java/fan/summer/fengyu/web/SpaForwardControllerTest.java`

- [ ] **Step 1: Write failing MVC route tests**

```java
class SpaForwardControllerTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SpaForwardController()).build();

    @Test void forwardsStaticSpaRoutes() throws Exception {
        for (String path : List.of("/", "/setup", "/tools", "/agent", "/plugins", "/settings", "/about", "/plugin/fan.summer.excel")) {
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test void doesNotClaimApiOrAssetRoutes() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isNotFound());
        mvc.perform(get("/assets/app.js")).andExpect(status().isNotFound());
        mvc.perform(get("/plugin-runtime/fan.summer.excel/ui/index.html")).andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run the test and confirm compilation failure**

Run: `./mvnw -pl FengYu -Dtest=SpaForwardControllerTest test`

Expected: FAIL because `SpaForwardController` does not exist.

- [ ] **Step 3: Implement explicit SPA route forwarding**

```java
@Controller
public final class SpaForwardController {
    @GetMapping({"/", "/setup", "/tools", "/agent", "/plugins", "/settings", "/about", "/plugin/{id}"})
    public String forward() { return "forward:/index.html"; }
}
```

- [ ] **Step 4: Add frontend resources to the Maven build**

Add after the normal resource entry in `FengYu/pom.xml`:

```xml
<resource>
    <directory>${project.basedir}/../frontend/dist</directory>
    <targetPath>static</targetPath>
    <filtering>false</filtering>
</resource>
```

- [ ] **Step 5: Verify routes and packaged static assets**

```bash
cd frontend && npm run build
cd ..
./mvnw -pl FengYu -Dtest=SpaForwardControllerTest test
./mvnw -pl FengYu -am package -DskipTests -Drevision=4.0.0-alpha.1
jar tf FengYu/target/FengYu-4.0.0-alpha.1.jar | rg '^static/(index.html|assets/)'
```

Expected: tests pass and the JAR contains `static/index.html` and frontend assets.

- [ ] **Step 6: Commit Web serving support**

```bash
git add FengYu/pom.xml FengYu/src/main/java/fan/summer/fengyu/web/SpaForwardController.java FengYu/src/test/java/fan/summer/fengyu/web/SpaForwardControllerTest.java
git commit -m "✨ feat(web): serve the Vue SPA from the backend jar"
```

### Task 4: Make the Tauri runtime resource-safe

**Files:**
- Modify: `desktop/src-tauri/src/main.rs`
- Modify: `desktop/src-tauri/tauri.conf.json`

- [ ] **Step 1: Add a failing Rust resource-layout test**

```rust
#[cfg(test)]
mod tests {
    use super::runtime_layout;
    use std::path::Path;

    #[test]
    fn runtime_layout_uses_tauri_resource_directory() {
        let layout = runtime_layout(Path::new("/app/resources"));
        assert_eq!(layout.jar, Path::new("/app/resources/binaries/FengYu.jar"));
        assert_eq!(layout.plugins, Path::new("/app/resources/plugins"));
    }
}
```

- [ ] **Step 2: Run the test and confirm failure**

Run: `cd desktop/src-tauri && cargo test runtime_layout_uses_tauri_resource_directory`

Expected: FAIL because `runtime_layout` does not exist.

- [ ] **Step 3: Implement the layout contract**

```rust
struct RuntimeLayout { jar: std::path::PathBuf, plugins: std::path::PathBuf }
fn runtime_layout(resource_dir: &std::path::Path) -> RuntimeLayout {
    RuntimeLayout {
        jar: resource_dir.join("binaries").join("FengYu.jar"),
        plugins: resource_dir.join("plugins"),
    }
}
```

Change `spawn_backend` and `run_backend_until_app_mode` to accept `&RuntimeLayout`. Remove working-directory candidate arrays and pass `layout.jar`/`layout.plugins` directly to Java.

- [ ] **Step 4: Start production backend inside Tauri setup**

Inside `tauri::Builder::setup`, obtain `app.path().resource_dir()?`, call `runtime_layout`, generate the token, start the backend, store the child in `Sidecar`, then create the window with the token/API initialization script. Keep the debug path external-backend-only with `#[cfg(debug_assertions)]` blocks.

- [ ] **Step 5: Package staged resources**

Replace stale hard-coded plugin resources in `tauri.conf.json` with:

```json
"resources": {
  "binaries/FengYu.jar": "binaries/FengYu.jar",
  "binaries/plugins/": "plugins/"
}
```

Set `beforeBuildCommand` to `cd ../../frontend && npm run build`; CI stages the tested JAR and `.fyp` files first.

- [ ] **Step 6: Verify Rust and JSON**

```bash
cd desktop/src-tauri
cargo fmt --check
cargo test
cargo check
python3 -m json.tool tauri.conf.json >/dev/null
```

Expected: all commands exit 0.

- [ ] **Step 7: Commit desktop packaging**

```bash
git add desktop/src-tauri/src/main.rs desktop/src-tauri/tauri.conf.json
git commit -m "🐛 fix(desktop): resolve bundled runtime resources through Tauri"
```

### Task 5: Build and smoke-test the portable Web archive

**Files:**
- Create: `distribution/web/run.sh`
- Create: `distribution/web/run.bat`
- Create: `distribution/web/README.md`
- Create: `scripts/package-web-release.sh`
- Create: `scripts/test-web-release.sh`

- [ ] **Step 1: Write the failing smoke script**

The script accepts a zip, extracts it, checks `Infinia.jar`, packages for `fan.summer.markdown`, `fan.summer.excel`, and `fan.summer.email`, and executable `run.sh`. It starts `run.sh --port=0`, reads `FENGYU_PORT=`, asserts `/` and `/api/health` return 200, then kills the process.

Run: `scripts/test-web-release.sh release/Infinia-4.0.0-alpha.1-web.zip`

Expected: FAIL because the package and script do not exist.

- [ ] **Step 2: Add Java 21 launch templates**

`run.sh` contains:

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "$JAVA" >/dev/null 2>&1 || { echo "Java 21 is required" >&2; exit 1; }
MAJOR="$($JAVA -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
[ "${MAJOR:-0}" -ge 21 ] || { echo "Java 21 is required" >&2; exit 1; }
exec "$JAVA" -Dfengyu.plugins.official-directory="$ROOT/plugins" -jar "$ROOT/Infinia.jar" "$@"
```

`run.bat` performs equivalent `%JAVA_HOME%\bin\java.exe`/`java.exe` lookup and launch. `README.md` documents Java 21, loopback-only access, and unsigned Alpha status.

- [ ] **Step 3: Implement deterministic assembly**

`scripts/package-web-release.sh VERSION JAR PLUGIN_DIR OUTPUT_DIR` validates inputs, creates `Infinia-$VERSION-web/`, copies templates/JAR/plugins, marks `run.sh` executable, and produces `Infinia-$VERSION-web.zip` plus `Infinia-$VERSION-web.tar.gz`. It fails unless all three official plugin IDs are present.

- [ ] **Step 4: Package and smoke-test locally**

```bash
mkdir -p release/official-plugins
cp OfficialPlugins/plugin-*/dist-package/*.fyp release/official-plugins/
scripts/package-web-release.sh 4.0.0-alpha.1 FengYu/target/FengYu-4.0.0-alpha.1.jar release/official-plugins release
scripts/test-web-release.sh release/Infinia-4.0.0-alpha.1-web.zip
```

Expected: layout checks pass, `/` serves Vue, `/api/health` returns 200, and cleanup succeeds.

- [ ] **Step 5: Commit Web distribution scripts**

```bash
git add distribution/web scripts/package-web-release.sh scripts/test-web-release.sh
git commit -m "✨ feat(release): add portable web distribution"
```

### Task 6: Replace the GitHub release workflow

**Files:**
- Replace: `.github/workflows/fengyu-release.yml`

- [ ] **Step 1: Add setup and shared-build jobs**

Use stable/alpha/beta/rc tag triggers plus `workflow_dispatch.tag`. `setup` calls:

```yaml
- id: version
  env:
    RELEASE_TAG: ${{ inputs.tag || github.ref_name }}
  run: node scripts/resolve-release-version.mjs "$RELEASE_TAG"
```

Expose tag/version/app-version/prerelease outputs. Manual dispatch checkout uses `ref: ${{ inputs.tag }}`. `build-runtime` installs Node 20 and Java 21, runs resolver/frontend tests, builds all three plugins through `plugin-cli`, builds the frontend with `FENGYU_RELEASE_VERSION`, runs Maven tests/package with `-Drevision`, verifies `static/index.html`, and uploads the JAR, `.fyp` files, and frontend output.

- [ ] **Step 2: Add Web packaging job**

Download shared inputs, call `scripts/package-web-release.sh`, smoke-test the zip, and upload both archives with `if-no-files-found: error`.

- [ ] **Step 3: Add desktop matrix job**

```yaml
strategy:
  fail-fast: false
  matrix:
    include:
      - { os: windows-latest, artifact: infinia-windows }
      - { os: macos-latest, artifact: infinia-macos }
      - { os: ubuntu-22.04, artifact: infinia-linux }
```

Install Node 20, Java 21, and stable Rust. Install Linux WebKit/AppIndicator/Rsvg/patchelf packages. Stage `desktop/src-tauri/binaries/FengYu.jar` and `binaries/plugins/*.fyp`, run `cargo tauri build --config '{"version":"<app_version>"}'`, and upload `desktop/src-tauri/target/release/bundle/**` with required-file behavior.

- [ ] **Step 4: Add release aggregation**

Download all artifacts, generate SHA-256 sums, and use `softprops/action-gh-release@v2` with the resolved tag/name, `prerelease` boolean, generated notes, and every artifact file. The body states unsigned packages, Java 21, Web launch commands, and loopback-only scope.

- [ ] **Step 5: Validate workflow syntax**

```bash
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/fengyu-release.yml"); puts "YAML OK"'
node --test scripts/resolve-release-version.test.mjs
if command -v actionlint >/dev/null; then actionlint .github/workflows/fengyu-release.yml; fi
git diff --check -- .github/workflows/fengyu-release.yml
```

Expected: YAML parses, tests pass, and actionlint has no errors when available.

- [ ] **Step 6: Commit the workflow**

```bash
git add .github/workflows/fengyu-release.yml
git commit -m "✨ feat(ci): publish alpha desktop and web artifacts"
```

### Task 7: Documentation and full verification

**Files:**
- Modify: `README.md`
- Modify: `desktop/README.md`
- Modify: `docs/en/quickstart.md`
- Modify: `docs/zh/quickstart.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Update release-affected documentation only**

Document unsigned Tauri and Web artifacts, Java 21, `run.sh`/`run.bat`, loopback-only scope, and deferred signing/JRE/updater work. Add one `Unreleased` changelog bullet. Do not version-sync historical changelog entries or `docs/superpowers/`.

- [ ] **Step 2: Run complete verification**

```bash
node --test scripts/resolve-release-version.test.mjs
cd frontend && npm test && npm run typecheck && FENGYU_RELEASE_VERSION=4.0.0-alpha.1 npm run build
cd ..
./mvnw test -Drevision=4.0.0-alpha.1
./mvnw package -DskipTests -Drevision=4.0.0-alpha.1
cd desktop/src-tauri && cargo fmt --check && cargo test && cargo check
cd ../..
scripts/package-web-release.sh 4.0.0-alpha.1 FengYu/target/FengYu-4.0.0-alpha.1.jar release/official-plugins release
scripts/test-web-release.sh release/Infinia-4.0.0-alpha.1-web.zip
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/fengyu-release.yml")'
git diff --check
```

Expected: all commands exit 0. Native Tauri bundle compilation remains CI-matrix acceptance if local platform packaging tools are unavailable; local Rust checks and resource tests must pass.

- [ ] **Step 3: Review scope and preserve user changes**

Run `git status --short` and inspect the diff while excluding the three pre-existing modified official-plugin `package-lock.json` files. Confirm they were not staged or altered by this work.

- [ ] **Step 4: Commit documentation**

```bash
git add README.md desktop/README.md docs/en/quickstart.md docs/zh/quickstart.md CHANGELOG.md
git commit -m "📝 docs(release): document alpha desktop and web packages"
```

- [ ] **Step 5: Report readiness without publishing**

Report passing commands, artifact names, Alpha limitations, and the eventual trigger:

```bash
git tag v4.0.0-alpha.1
git push origin v4.0.0-alpha.1
```

Do not create or push the tag unless the user separately authorizes the release operation.
