# Plugin Dependency Ownership Design

## Goal

Make Maven dependency ownership match the FengYu 4.0 isolated plugin architecture:
the repository may centrally manage versions for all in-repository modules, but the
host must not carry a library solely because a plugin needs it, and every plugin
Worker must be runnable from its own shaded JAR.

## Dependency Boundaries

The root `pom.xml` remains the single version-management source for the host,
`FengYu-Api`, `FengYu-Plugin-Sdk`, and the in-repository official plugins. Entries in
root `dependencyManagement` select versions for child modules; they do not declare
that the FengYu host supplies those libraries at runtime.

The `FengYu` module declares only dependencies used by host source code or required
by the host runtime. A dependency that is used exclusively by an official or external
plugin must not be present in `FengYu/pom.xml`. Libraries used independently by both
the host and a plugin may remain in both artifacts because the Worker runs in a
separate JVM.

Each official plugin backend is an isolated process launched from
`java -jar backend/worker.jar`. Every class needed by that process must therefore be
included in its shaded JAR. Runtime dependencies must use the default compile scope
or runtime scope as appropriate, never `provided` on the assumption that they are
available from the host fat JAR.

External plugins do not inherit FengYu's repository parent POM. They own their
dependency versions or import a separately published BOM and package their runtime
dependencies into their Worker artifact. Their compatibility contract with FengYu is
the manifest schema, JSON-RPC protocol, and published SDK versions, not the host's
implementation classpath.

## Planned Maven Changes

1. Keep central version properties and managed entries in the root `pom.xml` for
   in-repository modules. Update comments so they cannot be read as a runtime-sharing
   contract.
2. Remove dependencies from `FengYu/pom.xml` when no host production source imports
   or otherwise requires them. The current audit identifies Fesod Sheet, Apache POI,
   PDFBox, and Playwright as candidates. CommonMark, Gson, and Simple Java Mail remain
   because host production code uses them. Transitive/runtime requirements will be
   checked before removal.
3. Remove the unused `FengYu-Api` and Spring Context dependencies from the Markdown
   Worker. Its actual backend dependencies remain compile-scoped and shaded.
4. Keep Excel's currently referenced FengYu API, Spring, Spring AI, Jackson, Fesod,
   and POI dependencies compile-scoped so the Worker is self-contained. Correct stale
   comments that claim the host supplies those classes. Refactoring Excel's Java tool
   classes away from legacy annotations is outside this change.
5. Preserve test-only dependencies as test scope and do not package them into Worker
   artifacts.

## Compatibility Contract

This change does not alter plugin actions, JSON-RPC messages, manifest schema version
1, UI SDK negotiation, permissions, or package layout. It only makes the existing
process boundary explicit in Maven configuration.

`FengYu-Api` remains a versioned internal/legacy Java contract where code still
references it. `FengYu-Plugin-Sdk` remains the Worker protocol helper. Neither SDK
implies that arbitrary host implementation libraries are available to a Worker.

## Verification

Verification must prove both sides of the boundary:

- Resolve the effective Maven dependency trees and confirm plugin-only libraries are
  absent from the `FengYu` module unless host code requires them.
- Package the reactor or the affected modules successfully.
- Inspect each official Worker shaded JAR for representative runtime classes and its
  main class.
- Run the affected module tests and the existing official package build checks.
- Start representative Worker JARs through their JSON-RPC entry points or exercise
  their existing tests so success does not depend on the host classpath.

## Non-Goals

- Moving official plugin version management out of the root POM.
- Publishing a new public BOM.
- Refactoring Excel tool implementations or annotations.
- Changing plugin protocol, manifest, UI, or business behavior.
- Removing a dependency that the host uses for its own features merely because an
  official plugin also uses it.
