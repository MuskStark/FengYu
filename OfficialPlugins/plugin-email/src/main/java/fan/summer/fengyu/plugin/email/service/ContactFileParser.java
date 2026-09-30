package fan.summer.fengyu.plugin.email.service;

import fan.summer.fengyu.plugin.email.model.ContactImport.ParseError;
import fan.summer.fengyu.plugin.email.model.ContactImport.ParsedContact;

import java.nio.file.Path;
import java.util.List;

/**
 * Reads a contact-list file into the format-agnostic {@link ParsedContact} /
 * {@link ParseError} shapes that {@link ContactImporter} consumes. Implementations
 * detect their own format (CSV vs Excel) and own their I/O resources; callers
 * should {@link #close()} them when done.
 *
 * <p>Parsing is streaming and best-effort: malformed rows are reported as
 * {@link ParseError}s and skipped rather than aborting the whole file.
 */
interface ContactFileParser extends AutoCloseable {
    /** Parse the whole file into parsed contacts plus row-level errors. */
    Result parse(Path file);

    /** Holds the two parallel outputs of a parse. */
    record Result(List<ParsedContact> contacts, List<ParseError> errors) {
        public Result {
            contacts = contacts == null ? List.of() : List.copyOf(contacts);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    @Override void close();
}
