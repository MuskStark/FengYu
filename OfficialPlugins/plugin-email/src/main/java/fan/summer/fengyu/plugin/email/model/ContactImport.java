package fan.summer.fengyu.plugin.email.model;

import java.util.List;

/**
 * Records describing a contact batch-import run. Both the preview (dry-run) and
 * commit phases operate on the same {@link ParsedContact} shape, regardless of
 * the source file format (CSV or Excel), so the plan→commit logic is
 * format-agnostic.
 *
 * <p>A single import is intentionally split into two stateless calls — preview
 * computes what <em>would</em> happen without writing, commit re-parses the same
 * file and applies the same options atomically. No in-worker state is held
 * between the two.
 */
public final class ContactImport {
    private ContactImport() { }

    /** One normalized row from the source file, before any database diffing. */
    public record ParsedContact(int row, String email, String nickname, String notes, List<String> tags) {
        public ParsedContact {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    /** A row-level problem (bad header, blank email, malformed address). Import continues. */
    public record ParseError(int row, String message) { }

    /** How existing contacts and tag delimiters should be handled. Symmetric on preview and commit. */
    public record ImportOptions(String duplicateMode, String tagDelimiter) {
        public ImportOptions {
            if (duplicateMode == null || duplicateMode.isBlank()) duplicateMode = "merge";
            if (tagDelimiter == null || tagDelimiter.isBlank()) tagDelimiter = "auto";
        }
    }

    /** Dry-run result: counts of what would happen, the new tag names that would be created, and row errors. */
    public record ImportPreview(int rowsTotal, int rowsValid,
        int createdContacts, int mergedContacts, int skippedContacts,
        List<String> createdTags, List<ParseError> errors) {
        public ImportPreview {
            createdTags = createdTags == null ? List.of() : List.copyOf(createdTags);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    /** Final counts after an atomic commit, plus the row errors that did not abort the import. */
    public record ImportResult(int created, int merged, int skipped,
        int tagsCreated, int tagsAssigned, List<ParseError> errors) {
        public ImportResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }
}
