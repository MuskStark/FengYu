Run `bash scripts/sync-plugin-standards.sh` to snapshot docs/plugins into the
plugin-kit standards/ and stamp the current SwissKitJ-Api version. After it
runs, review the diff under `.claude-plugin/plugin/standards/` and commit.
Note: `standards/checklist.md` is hand-maintained — update it only when
validation rules change, not on every sync.
