# Release Skill - Publish Stable Version

Publish a new stable release version of SwissKitJ.

## Usage

`/release <version>`

## Arguments

- **version**: Semantic version string (e.g., `1.0.0`, `1.1.0`, `2.0.0-rc.1`)

## Workflow

This skill executes a multi-agent release process:

### 1. Software Development Agent (SDA)

- Update main `pom.xml` version to specified version
- Update `OfficalPlugin/SwissKitJ-Plugin-HappyLearning/pom.xml` version to specified version
- Update `OfficalPlugin/SwissKitJ-Plugin-Qcc/pom.xml` version to specified version (if not gitignored)
- Create/update `CHANGELOG.md` at project root with:
  - Software introduction
  - Feature summary
  - Installation guide (Windows, Linux, macOS)
  - Developer information
  - Full version history

### 2. Testing Agent (TA)

- Verify all version updates are correct
- Confirm CHANGELOG.md is properly formatted
- Note: Full build verification is delegated to GitHub Actions

### 3. CI/CD Agent

- Validate `.github/workflows/release.yml` configuration
- Create git tag: `v{version}` (e.g., `v1.0.0`)
- Commit changes with release message
- Push commit and tag to remote

## GitHub Actions

After tag is pushed, GitHub Actions will automatically:
1. Build for 3 platforms (Windows, Linux, macOS)
2. Create GitHub Release with all artifacts
3. Upload platform-specific ZIP packages and checksums

## Example

```
/release 1.0.0
```

This will:
1. Update all pom.xml versions to `1.0.0`
2. Create comprehensive `CHANGELOG.md`
3. Commit: "📦 Release: Bump version to 1.0.0"
4. Create and push tag `v1.0.0`
5. Trigger GitHub Actions release workflow

## Notes

- Maven is NOT required locally - build verification happens in CI/CD
- GitHub Actions must be configured for automatic builds
- The release workflow supports semantic versioning patterns:
  - Stable: `v1.0.0`, `v2.1.0`
  - Pre-release: `v1.0.0-alpha.1`, `v1.0.0-beta.1`, `v1.0.0-rc.1`