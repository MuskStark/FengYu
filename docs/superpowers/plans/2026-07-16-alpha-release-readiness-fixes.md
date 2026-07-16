# Alpha Release Readiness Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the CI, Tauri build, and first-launch blockers found during Alpha release review.

**Architecture:** Add executable contracts for release workflow invariants and desktop startup
decisions. Keep the existing resource layout, but create the window before supervising a SETUP-mode
backend and restart that backend on the already-injected port.

**Tech Stack:** Node 24.18.0 test runner, GitHub Actions YAML, Rust/Tauri 2, Java 21, Maven, Vue/Vite.

## Global Constraints

- Preserve the existing unsigned, loopback-only Alpha release scope.
- Use the full semantic release version for Maven artifacts and numeric-only version for Tauri.
- Do not create or push a release tag.

---

### Task 1: Release Workflow Contracts

**Files:**
- Create: `scripts/release-workflow.test.mjs`
- Modify: `.github/workflows/fengyu-release.yml`

- [ ] Write tests asserting the setup step does not override `GITHUB_OUTPUT`, `plugin-cli` runs
  `npm ci`, Maven uses `$VERSION`, and the Tauri hook points from `desktop/` to `../frontend`.
- [ ] Run `node --test scripts/release-workflow.test.mjs` and confirm all four checks fail.
- [ ] Apply the four minimal configuration fixes.
- [ ] Re-run the contract test and release resolver tests.

### Task 2: Non-Blocking Desktop Setup Restart

**Files:**
- Modify: `desktop/src-tauri/src/main.rs`

- [ ] Add Rust tests for the startup decision: APP returns immediately without supervision; SETUP
  returns immediately and requests supervision on the selected port.
- [ ] Run the targeted Rust tests and confirm the SETUP expectation fails.
- [ ] Replace the blocking SETUP loop with immediate startup metadata and a background supervisor
  that polls the managed child and restarts it on the same port after exit code 0.
- [ ] Run `cargo fmt --check`, `cargo test`, and `cargo check`.

### Task 3: Full Release Verification

**Files:**
- Verify all modified files and generated artifacts; do not commit generated output.

- [ ] Build the Alpha frontend and confirm its assets contain `4.0.0-alpha.1`.
- [ ] Run the full Maven reactor tests and package the Alpha JAR.
- [ ] Confirm the JAR contains `static/index.html` and assets.
- [ ] Stage the JAR/plugins and run `cargo tauri build --debug --no-bundle`.
- [ ] Assemble and smoke-test the Web ZIP.
- [ ] Parse the workflow YAML, run `git diff --check`, and inspect `git status --short`.
