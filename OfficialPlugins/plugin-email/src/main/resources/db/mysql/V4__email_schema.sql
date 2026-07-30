-- V4 finalizes the brand-prefix correction to FENGYU_PL. The actual FengTu_PL_* -> FENGYU_PL_*
-- table/index renames are performed in Java (SchemaMigrator.renameLegacyPrefixedTables) so the
-- step is cross-dialect, no-op on fresh installs, and re-runnable. This script records the version.
INSERT INTO FENGYU_PL_Email_Schema_History(version) VALUES (4);
