# Alpha Release Readiness Fixes Design

## Goal

Make the existing `v4.0.0-alpha.1` Web and desktop release implementation buildable in a clean
GitHub Actions runner and usable on the first desktop launch.

## Release Workflow

The release setup step relies on the runner-provided `GITHUB_OUTPUT` path and must not override it.
The shared runtime job installs `plugin-cli` dependencies before invoking the CLI. Maven receives
the full release version (`4.0.0-alpha.1`) so the produced JAR name matches the artifact staging
steps; the numeric version (`4.0.0`) remains limited to Tauri's bundle version.

A Node contract test reads the workflow and protects these relationships without attempting to
emulate GitHub Actions itself.

## Desktop Build And Lifecycle

Tauri runs build hooks from `desktop/`, so the production frontend hook uses `cd ../frontend`.

Production startup spawns the backend and waits for health, then creates the webview immediately in
both SETUP and APP modes. If the backend is in SETUP mode, a supervisor polls the managed child
without blocking the Tauri setup callback. After a successful setup exit it restarts the backend on
the exact port originally injected into the webview, waits for health, and replaces the managed
child. Closing the window can still kill whichever child is active.

Unexpected setup exits or restart failures are logged and stop supervision; they do not loop
forever. APP mode does not start a supervisor.

## Verification

Verification covers the workflow contract, release resolver, frontend tests/typecheck/Alpha build,
the full Maven reactor and packaged SPA, Rust formatting/tests/checks, a Tauri no-bundle build, and
portable Web archive smoke testing. No tag or release is created.
