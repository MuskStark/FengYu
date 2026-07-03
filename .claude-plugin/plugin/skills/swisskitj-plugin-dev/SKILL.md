---
name: swisskitj-plugin-dev
description: Use when a developer wants to create a new SwissKitJ plugin. Scaffolds a standards-compliant plugin project in an independent repo and runs compliance checks.
---

# SwissKitJ Plugin Scaffolder

Scaffolds a new SwissKitJ plugin into the developer's **own, independent repo** (not this
host repo) and verifies it against the host's design standards before declaring it done.

This skill owns *how to assemble* a plugin project. It does not restate *why* the rules
exist or their full detail — those live in `standards/*.md` inside this same plugin bundle
(resolve paths relative to this SKILL.md's own directory, i.e. `../../standards/`,
`../../scripts/validate.sh`, `../../templates/CLAUDE.md.tmpl`). Whenever you need the exact
shape of a layout rule, the AiTool JSON contract, PluginHost API, i18n conventions, or the
database pattern, **read the matching standards doc** instead of relying on memory or
reproducing it here — the standards docs are the single source of truth and may have moved
ahead of this skill.

Follow these five steps in order. Do not skip step 2 or step 5.

## Step 1 — Ask requirements

Ask the developer for (do not guess silently if any are missing):

1. **Plugin name** (e.g. `StarReport`) — used as the Java "Name" token (`{{Name}}`), artifact ID, and project folder name.
2. **Plugin ID** (reverse-domain, e.g. `plugin.swisskit.star`) — at least two dot-separated segments; this becomes `getId()`.
3. **Base package** (e.g. `plugin.swisskit.star`) — Java package root. Often identical to the plugin ID.
4. **Short description** — shown in the host UI.
5. **Category** — MUST be exactly one of `DEV`, `TEXT`, `IMAGE`, `NET`, `OTHER` (maps to `ToolCategory`). Reject any other value and ask again.
6. **MDI icon name** — Material Design Icons name without the `mdi-` prefix (e.g. `file-excel`). See https://pictogrammers.com/library/mdi/.
7. **Optional modules** — ask yes/no for each: needs a database (H2 + MyBatis)? needs Excel I/O? needs AI tool integration? needs background tasks?

Derive a lowercase `{{slug}}` from the plugin name (e.g. `StarReport` → `starreport` or a
short existing convention like `star`) — used for i18n key prefixes (`plugin.<slug>.`) and
DB path suffixes (`pl_<slug>`).

## Step 2 — Load current standards FIRST

Before generating a single file, read from **this same plugin bundle**:

- `../../standards/VERSION` — the current SwissKitJ API version. Use this exact string as
  the `swisskit.api.version` property value in the generated `pom.xml`. **Never hardcode a
  version number in this skill file** — always read it fresh, since it changes across host
  releases.
- `../../standards/checklist.md` — the full M1–M12 (mechanical) and S1–S6 (semantic) rule
  set the scaffolded project must satisfy.

If optional modules were requested, also read the matching doc now (not later):
`../../standards/database.md` (DB), `../../standards/ui.md` + `../../standards/pitfalls.md`
(layout/theming, always relevant), `../../standards/i18n.md`, `../../standards/plugin-host.md`
(background tasks / AI / settings).

## Step 3 — Assemble the project

Create the project at a path the developer specifies (their own independent repo, not
inside this host repo). Always emit the base skeleton; optional modules are additive.

### Base skeleton (always emitted)

Mirror the KeepAwake reference plugin's **real shapes** exactly — same package layout,
same class responsibilities, same file set:

```
<plugin-name>/
├── pom.xml
├── src/main/java/<base-package-path>/
│   ├── <Name>Plugin.java          # SPI entry point (implements SwissKitJPlugin)
│   ├── DevLauncher.java           # zero JavaFX imports (see below)
│   └── ui/
│       └── <Name>PluginUi.java    # JavaFX UI, returns a Node via getView()
└── src/main/resources/
    ├── META-INF/services/fan.summer.api.SwissKitJPlugin
    └── i18n/
        ├── messages.properties       # English (default)
        └── messages_zh.properties    # Chinese
```

**pom.xml** — standalone (no parent), Java 21, `<finalName>${project.artifactId}-${project.version}</finalName>`:
- `SwissKitJ-Api` dependency, `scope=provided`, `<version>${swisskit.api.version}</version>`
  where `swisskit.api.version` is a `<properties>` entry set to the value read in Step 2.
- `javafx-graphics` + `javafx-controls`, `scope=provided` (host supplies at runtime).
- `maven-shade-plugin` bound to the `package` phase with
  `<transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>`
  and `<createDependencyReducedPom>false</createDependencyReducedPom>` — **required**, or the
  fat JAR's SPI file gets clobbered (see `../../standards/spi.md`).
- A `dev` Maven profile that re-adds `javafx-graphics`/`javafx-controls` as **non-provided**
  dependencies and configures the `javafx-maven-plugin` (`org.openjfx:javafx-maven-plugin`)
  with `<mainClass>{{base-package}}.DevLauncher</mainClass>`.
- Reuse the exact KeepAwake `pom.xml` structure (compiler plugin, surefire, lombok if
  desired) as your literal template — do not redesign it.

**`<Name>Plugin.java`** — implements `fan.summer.api.SwissKitJPlugin`. Follow
`../../standards/entry-point.md` for the exact method bodies. Key facts to get right:
- `getCategory()` returns a `ToolCategory` enum constant (not a string) — one of
  `DEV/TEXT/IMAGE/NET/OTHER` per the developer's answer in Step 1.
- `getIconStyle()` returns an `IconStyle` enum constant (default `IconStyle.BLUE` if the
  developer has no preference); maps to an `ic-*` CSS class on the host side, nothing to do
  here.
- `getId()` returns the reverse-domain plugin ID from Step 1 verbatim.
- `createView()` registers the i18n bundle **before** constructing the UI, then returns
  `new <Name>PluginUi().getView()`. Use `I18n.registerPluginBundle("i18n.messages",
  getClass().getClassLoader())` for the baseline scaffold (mirrors KeepAwake exactly). If the
  developer wants PluginHost-based settings/tasks/theme access (see optional modules below),
  additionally implement `init(PluginHost host)` and register via
  `host.i18n().registerBundle("i18n.messages")` instead — see
  `../../standards/plugin-host.md`.
- Do not fabricate lifecycle methods (`onActivate`/`onBackground`/etc.) unless the plugin
  actually needs them; empty default methods are fine to omit entirely.

**`DevLauncher.java`** — **zero `import javafx...` lines** (checked literally by `validate.sh`
M11). Call `Platform.startup` through its fully-qualified name (`javafx.application.Platform.startup(...)`)
instead of importing it — this keeps the runtime behavior identical to the KeepAwake reference
(which itself imports `javafx.application.Platform`) while satisfying the mechanical grep-based
check. All other JavaFX code lives inside `<Name>Plugin`/`<Name>PluginUi`. Body is exactly:

```java
package {{base-package}};

import fan.summer.api.preview.PluginPreviewWindow;

public class DevLauncher {
    public static void main(String[] args) {
        javafx.application.Platform.startup(() -> {
            PluginPreviewWindow.configure().withPlugin(new {{Name}}Plugin()).launch();
        });
    }
}
```

This is a hard mechanical rule (M11) — any `import javafx...` line here breaks `mvn
javafx:run -Pdev` with a module-system error (per `validate.sh`'s check); using the
fully-qualified name for `Platform.startup` avoids the import entirely.

**`<Name>PluginUi.java`** — plain JavaFX `Node` builder, cached and returned once via
`getView()`. Follow `../../standards/ui.md` for the base structure, i18n binding pattern
(`I18n.bind(...)`), and **all** JavaFX layout-pitfall rules (`../../standards/pitfalls.md`)
— do not use `setPrefWidth(Double.MAX_VALUE)`, do not self-bind `maxWidthProperty()` to
`widthProperty()`, use `.sk-*` style classes for chrome (never `.glass-*`, which was
renamed away in host v3.2.0 and is now banned). If the UI opens its own `Alert`/`Stage`,
apply the theme per `../../standards/ui.md`'s `Themes.applyTo(scene)` pattern.

**SPI file** — `src/main/resources/META-INF/services/fan.summer.api.SwissKitJPlugin`,
single line, the entry class FQN only:

```
{{base-package}}.{{Name}}Plugin
```

**i18n** — `src/main/resources/i18n/messages.properties` (English/default) and
`messages_zh.properties` (Chinese), **identical key sets**, all keys prefixed
`plugin.{{slug}}.`. Follow `../../standards/i18n.md`.

### Optional modules (only if requested in Step 1)

Each optional module is a **minimal** skeleton — enough to compile and demonstrate the
pattern, not a full feature. Point the developer at the matching standards doc for anything
beyond the skeleton; do not inline the full pattern here.

- **Database (H2 + MyBatis)** — add `h2` + `mybatis` dependencies, a `database/DatabaseInit.java`,
  `src/main/resources/mybatis-config.xml`, and one example mapper interface + XML under
  `database/mapper/` + `src/main/resources/mapper/`. Copy the exact `DatabaseInit` shape
  (H2 URL built from `user.dir`, forward slashes only) from `../../standards/database.md` —
  do not hand-roll the URL construction.
- **Excel I/O** — add the `org.apache.fesod:fesod-sheet` dependency and one example DTO +
  `ReadListener` under `excel/dto/` and `excel/listener/`. Full read/write patterns are in
  the project's own `CLAUDE.md` "Excel Splitter" reference material if the developer's host
  repo has it, otherwise keep to fesod's own docs — this skill does not carry Excel
  templates beyond the minimal DTO/listener pair.
- **AI tool integration** — implement `aiTools()` on the entry class returning a `List<AiTool>`.
  Point the developer at the project's AI tool contract (`AiToolResult.success/error` JSON
  envelope with `{success, summary, ...}` / `{success:false, error}` — this is checked by
  the reviewer's S1 rule) and `supportsLocal()/supportsCloud()` capability flags. Do not
  register tools manually — the host auto-registers/unregisters via the plugin registry.
- **Background tasks** — if `init(PluginHost host)` is implemented, submit long-running work
  via `host.tasks().submit(name, backgroundWork, onSuccessFx, onErrorFx)` per
  `../../standards/plugin-host.md`. **Never** spawn a bare `new Thread(...)` or ad-hoc
  `Executors.newXxx(...)` for plugin work that should keep the plugin backgrounded — that is
  reviewer rule S2.

## Step 4 — Drop CLAUDE.md and validate.sh into the new repo

1. Read `../../templates/CLAUDE.md.tmpl`, fill its `{{...}}` placeholders with the values
   collected in Step 1 (and the `swisskit.api.version` read in Step 2), and write the result
   to `CLAUDE.md` at the new repo's root.
2. Copy `../../scripts/validate.sh` verbatim into the new repo's root as `validate.sh`. This
   is the developer's own local copy going forward — the standing instruction in their new
   `CLAUDE.md` tells them (and future Claude Code sessions in that repo) to run it after
   every change.

## Step 5 — Validate and fix before declaring done

Run, from the new repo root:

```bash
bash validate.sh .
```

This checks mechanical rules M1–M12. If it prints any `FAIL` line, fix the scaffolded files
(not `validate.sh`) and re-run until it prints `VALIDATE OK: .` with zero `FAIL` lines.

Then invoke the `swisskitj-plugin-reviewer` agent against the new project directory to check
the semantic rules S1–S6 (AiTool JSON contract, `host.tasks()` usage, `Themes.applyTo`,
H2 path conventions, `createView()` caching discipline, `-sk-*` token usage). Fix every
reported violation and re-invoke the reviewer until it reports `SEMANTIC OK`.

Only declare the scaffold complete once both `bash validate.sh .` is clean and the
`swisskitj-plugin-reviewer` agent reports `SEMANTIC OK`.
