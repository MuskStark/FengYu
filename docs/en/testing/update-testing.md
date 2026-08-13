# Application Update — Manual Test Checklist

End-to-end verification of the "detect update → user consents → self-download/install/restart"
flow across all three deployment modes. Each section is self-contained: run it on the target
platform with the listed prerequisites.

> The unit tests (`UpdateCheckServiceTest`, `portable-updater.test.ts`, `auto-updater.test.ts`)
> cover version comparison and detection logic. This checklist verifies the **real** download →
> verify → swap → relaunch pipeline, which must be exercised against a real GitHub release or an
> FY-Proxy intranet repository on the target OS.

---

## Shared prerequisites (all modes)

1. **Two releases must exist on GitHub** so "old" can detect "new":
   - An **older** tagged release you will install/run (e.g. `v4.0.0-beta.1`).
   - A **newer** tagged release available on the Releases page (e.g. `v4.0.0-beta.4`).
   - The newer release MUST have been built by `fengyu-release.yml` AFTER the changes in this
     feature landed (so `latest*.yml`, `*.blockmap`, and `checksums.txt` are published — required
     by electron-updater and the portable self-updater).
2. Confirm the newer release's assets include the ones your mode needs (see below).
3. Have the older build installed/extracted and running before you trigger the check.

### How to confirm the newer release has the required feed files

Open `https://github.com/MuskStark/FengYu/releases/tag/v<NEW_VERSION>` and confirm these assets
exist (if any is missing, the CI change did not take effect and auto-update cannot work):

| Asset | Required by |
|---|---|
| `latest.yml` (Windows) / `latest-mac.yml` (macOS) | electron-updater (NSIS install + macOS) |
| `*.blockmap` | electron-updater differential download |
| `Infinia-<ver>-win32-x64-portable.zip` | Windows portable self-update |
| `Infinia.jar` + `checksums.txt` | Portable Web (java -jar) self-update |

---

## Intranet / offline FY-Proxy mode

Set **Settings → Update channel → Update proxy URL** to the FY-Proxy origin, for example
`http://10.0.0.5:8088`. The value is persisted and loaded before the desktop window opens, so both
the startup probe and **About → Check for updates** avoid GitHub.

FY-Proxy accepts and publishes only these two asset classes:

| Platform/package | Required filename | Discovery endpoint |
|---|---|---|
| Windows portable x64 | `Infinia-<version>-win32-x64-portable.zip` | `/fengyu-releases/api/releases/latest?channel=windows-portable` |
| Debian/Ubuntu lite x64 | `Infinia-<version>-linux-x64.deb` | `/fengyu-updates/deb/latest-linux.yml` |

Upload the asset from FY-Proxy's **File management** page (`/files`) with the exact version from
its filename. FY-Proxy rejects mismatched versions and all other package types. For portable ZIP,
the release response carries a SHA-256 digest that Infinia verifies before extraction; the deb feed
uses electron-updater's SHA-512 verification. Confirm automatic discovery after launch, then use
**About → Recheck** to confirm manual discovery. NSIS, AppImage, macOS, JRE, and portable Web/JAR
updates are deliberately unsupported in intranet mode.

---

## Mode 1: Windows NSIS install (`*-setup.exe`)

**Platform:** Windows 10/11 · **Update mechanism:** electron-updater

> Public GitHub channel only; FY-Proxy rejects NSIS assets.

### Setup
1. Download and install the **older** `*-win-x64-setup.exe`.
2. Launch Infinia from the Start menu / desktop shortcut.

### Steps
1. Open **About** (sidebar). The "Update" row should show
   `Version <NEW_VERSION> is available` after a few seconds (the StatusBar badge also appears).
2. Click **Update now**. A confirmation popover appears warning the build is unsigned.
3. Click **Continue**.
4. A download progress indicator fills (the `update:progress` IPC events flow).
5. On completion the app **quits and the NSIS installer runs silently**, then Infinia relaunches.
   - Windows SmartScreen MAY warn (unsigned build) — click "More info" → "Run anyway". This is
     expected for unsigned builds.
6. After relaunch, open **About** again — the version should now read `<NEW_VERSION>`, and the
   "Update" row should say "Up to date".

### Pass criteria
- [ ] Update detected on the older build
- [ ] Download progress visible
- [ ] App quits, installer runs, app relaunches
- [ ] New version confirmed in About after relaunch
- [ ] StatusBar badge gone after update

### Common failures
| Symptom | Likely cause |
|---|---|
| "Update check failed" in About | `latest.yml` not published on the release (CI glob missing) |
| Download never starts | `signedRelease` gating — the consent IPC path should bypass it; check `update:download-install` is reached (DevTools → Network/Console) |
| App quits but doesn't relaunch | NSIS `--updated` flow failed; check the installer log in `%TEMP%` |

---

## Mode 2: Windows portable zip (`*-portable.zip`)

**Platform:** Windows 10 1803+ · **Update mechanism:** custom pipeline (`portable-updater.ts`)

> electron-updater CANNOT update a portable zip — this mode uses a custom download → tar extract
> → robocopy replace → relaunch pipeline. This is the mode most likely to surface file-lock or
> single-instance issues, so test it carefully.

### Setup
1. Download and extract the **older** `*-win-x64-portable.zip` to a folder, e.g.
   `C:\Users\<you>\Infinia\`.
2. Run `C:\Users\<you>\Infinia\Infinia.exe`.

### Steps
1. Open **About** → the "Update" row detects `<NEW_VERSION>` (via the GitHub API or the configured
   FY-Proxy `windows-portable` release endpoint, not `latest.yml`).
2. Click **Update now** → **Continue** (unsigned warning).
3. Download progress fills (the portable zip is large; watch the percent).
4. The app **quits**. A detached `.bat` (in `%TEMP%`) takes over:
   - Waits for the old `Infinia.exe` PID + backend JVM tree to exit (tasklist polling).
   - `robocopy`s the extracted new tree over the install folder.
   - Restarts `Infinia.exe`.
5. Infinia relaunches automatically.

### Pass criteria
- [ ] Portable detection worked (About shows an update, NOT a silent failure)
- [ ] Download progress visible
- [ ] Old process fully exits before file replacement (no "file in use" error)
- [ ] `Infinia.exe` + `resources\binaries\FengYu.jar` + `resources\app.asar` all replaced
- [ ] App relaunches with the new version (About shows `<NEW_VERSION>`)
- [ ] No second-instance error on relaunch (single-instance lock released cleanly)

### How to inspect the update if it fails
- The detached bat log: `%TEMP%\fengyu-portable-update-<pid>.log`
- The bat itself (before self-delete): `%TEMP%\fengyu-portable-update-<pid>.bat`
- Staging dir: `%TEMP%\fengyu-portable-update-*\` (extracted new tree)
- Run from a cmd window to see console output: `Infinia.exe` logs to
  `<runtime>\.fengyu\logs\`.

### Common failures
| Symptom | Likely cause |
|---|---|
| `tar extraction failed` | Windows older than 10 1803 (no bsdtar); or the zip asset name doesn't contain `-portable.zip` |
| robocopy reports "file in use" | Backend JVM not fully dead before replacement — the PID-wait loop in the bat needs a longer grace period |
| App relaunches but immediately exits | Single-instance lock not released (old process still alive); or the new exe path differs |
| About never shows an update | `isWindowsPortable()` returned false — confirm `resources\app-update.yml` is ABSENT in the portable extract |

---

## Mode 3: Portable Web (`java -jar`, run.sh / run.bat)

**Platform:** any (macOS/Linux/Windows) · **Update mechanism:** backend `SelfUpdateService`
(JAR download → SHA256 verify → detached restart script → JVM exit → swap → relaunch)

> This is the only mode testable end-to-end on macOS/Linux. It swaps only the JAR (the launcher
> scripts and plugins stay put). This mode is available only through GitHub; FY-Proxy rejects JAR
> assets.

### Setup
1. Download and extract the **older** `Infinia-<OLD>-web.zip` (or `.tar.gz`).
2. Launch it:
   - macOS/Linux: `./run.sh`
   - Windows: `run.bat`
3. Note the generated token printed to stderr (`Generated per-launch token ...: zf-...`).
4. Open the UI in a browser at the printed URL (or use curl with the token).

### Steps
1. Open **About** → "Update" row shows `<NEW_VERSION>` is available.
   - The release MUST have an `Infinia.jar` asset AND a `checksums.txt` asset (both published by
     the release workflow). Without `checksums.txt`, the SHA256 verification step fails.
2. Click **Update now** → **Continue**.
3. The backend:
   - Downloads `Infinia.jar` from the release to a staging file.
   - Downloads `checksums.txt`, parses the `Infinia.jar` line, verifies SHA256.
   - Generates `self-update.sh` (POSIX) or `self-update.bat` (Windows) into
     `<runtime>\runtime-files\`.
   - Spawns the script detached, then exits the JVM (`System.exit` after a 1s flush delay).
4. The detached script:
   - Waits for the old JVM PID to exit (`tail --pid=` on POSIX / `tasklist` on Windows).
   - Backs up the old JAR to `Infinia.jar.bak`.
   - Moves the downloaded JAR into place.
   - Re-launches `java -jar Infinia.jar <original args>`.
5. The new backend comes up; the browser reconnects (StatusBar dot returns to green).

### Pass criteria
- [ ] Update detected (About shows `<NEW_VERSION>`)
- [ ] Download + SHA256 verification succeed (check the backend log for
      `[self-update] checksum verified`)
- [ ] Old JVM exits cleanly (backend log shows context close + shutdown hooks)
- [ ] JAR replaced: `Infinia.jar` is the new version, `Infinia.jar.bak` is the old
- [ ] New JVM restarts with the same port/token (UI reconnects without manual action)
- [ ] About now shows `<NEW_VERSION>` and "Up to date"

### How to inspect the update if it fails
- Backend log: `<extract>\data\logs\` (the `-Dfengyu.runtime.dir=$ROOT/data` location).
- Restart script: `<extract>\data\runtime-files\self-update.sh` (or `.bat`).
- Restart script log: `<extract>\data\runtime-files\self-update-*.log`.
- Staging download: `<extract>\data\runtime-files\update-staging-*.jar`.

### Common failures
| Symptom | Likely cause |
|---|---|
| `SHA-256 mismatch for Infinia.jar` | `checksums.txt` on the release is stale or doesn't match the published JAR |
| `checksums.txt has no entry for Infinia.jar` | The release was built before `checksums.txt` was added to the CI collect step |
| Old JAR not replaced (still old version after restart) | Detached script wasn't truly detached (process died with the JVM); or `java.class.path` didn't resolve to the JAR path |
| JVM exits but nothing restarts | Script's relaunch command is wrong — inspect `self-update.sh`, check the `exec java ...` line has the right `-D` flags and `--token` |
| Restarts but on a different port | The relaunch didn't carry `--port=<n>` through; `sun.java.command` parsing in `SelfUpdateService.buildRelaunchCommand` missed it |

---

## Mode 4: macOS desktop (`*-mac-arm64.dmg`)

**Platform:** macOS (Apple Silicon) · **Update mechanism:** manual download (unsigned Gatekeeper fallback)

> macOS unsigned builds CANNOT auto-relaunch after a `quitAndInstall` (Gatekeeper blocks the
> replaced bundle). The app degrades to "download + open releases page". This is intentional
> until code-signing + notarization lands. FY-Proxy intranet mode does not support macOS.

### Setup
1. Download and install the **older** `*-mac-arm64.dmg` (drag Infinia to Applications).
2. On first launch, right-click → Open (to bypass Gatekeeper for the unsigned old build).
3. Run Infinia.

### Steps
1. Open **About** → "Update" row detects `<NEW_VERSION>`.
   - Note: the About page detects macOS and shows an **"Open page"** button instead of
     "Update now" (because the shell's `update:download-install` IPC returns `{action:'manual'}`
     on darwin).
2. Click **Open page** → browser opens the GitHub releases page.
3. Download the **newer** `*-mac-arm64.dmg` manually.
4. Replace the old Infinia.app in /Applications (drag the new one in, agree to replace).
5. Right-click → Open the new Infinia (Gatekeeper warning again — unsigned).

### Pass criteria
- [ ] Update detected
- [ ] "Open page" button shown (NOT "Update now" — confirms the darwin branch)
- [ ] Releases page opens to the right URL
- [ ] After manual replacement, new version confirmed in About

### Future: when code-signing lands
Re-run Mode 4 after a signed+notarized build: the darwin branch in `ipc/update.ts` should be
changed to call `downloadUpdate()` + `quitAndInstall()` (same as Windows NSIS), and the About
button should switch to "Update now". At that point the full auto flow applies.

---

## Cross-mode sanity checks (quick, after any successful update)

Regardless of mode, after a successful update verify the app is fully functional, not just
"new version number":

- [ ] **Plugins still load**: open Tools — the 5 official plugins appear (markdown/excel/email/
      offlinepython/browser). The updated JAR/asar must have shipped matching plugin packages.
- [ ] **AI chat works**: send a message in Chat — a response streams back.
- [ ] **Database intact**: open Settings — the DB config persisted across the restart (the
      `data/` runtime dir was NOT wiped by the update).
- [ ] **Re-check returns "up to date"**: About → "Recheck" → now says "Up to date" (the new
      build's version equals the latest release tag).
