# Changelog

All notable changes to SwissKitJ. Format based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [3.0.0] — JavaFX Migration

**v3.0.0-rc.1** — 2026-06-04

### ✨ New Features

- **Browser Automation**: AI-callable `browser_automate` tool that automates web browsers via natural language; uses Playwright with the system's installed Chrome/Edge/Chromium (no separate browser download); observe-think-act loop with page DOM snapshots, CSS selector targeting, and a planner LLM
- **Resizable Window**: Edge and corner drag resize for the undecorated `StageStyle.TRANSPARENT` window via `WindowResizeHelper`; uses screen coordinates for macOS compatibility
- **Responsive Layout**: Dynamic `FlowPane` wrap length bound to viewport width; `windowPane` and `ContentArea` properly fill parent with `setMaxWidth/Height(Double.MAX_VALUE)`
- **Pure Java PDF-to-DOCX**: `PdfBoxToDocxConverter` using PDFBox for extraction and Apache POI for DOCX generation — no external Office installation required; three-tier page strategy (text → extracted images → full-page render fallback)
- **Native Backend Health Tracking**: `NativeLoader.FailureReason` enum for structured failure diagnostics; degraded-mode banner in AI chat when native acceleration is unavailable

### 🔧 Fixes

- Fix recursive tool calls in browser automation — planner bypasses `AiService` tool injection via direct HTTP call
- Fix window resize blocked on macOS — stop trusting `stage.isMaximized()` with `StageStyle.TRANSPARENT`
- Fix viewport wrap-length binding against zero width
- Fix Playwright runtime browser download — set `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD`
- Fix `LoadState` import and `record` accessor syntax for Playwright API

### ♻️ Changes

- Replace Office-dependent PDF converter with pure Java implementation (PDFBox + POI); remove `WpsConverter`, `Documents4jConverter`, and `OfficeDetector`
- Remove dev-only planning docs and eval workspace data

---

**v3.0.0-beta.2** — 2026-05-26
