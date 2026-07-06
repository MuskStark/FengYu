package fan.summer.zhiflow.buildintool.ai;

import fan.summer.zhiflow.api.ai.*;
import fan.summer.zhiflow.ai.util.JsonHelper;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import fan.summer.zhiflow.buildintool.emailarchive.*;

import java.nio.file.*;
import java.util.*;

public class EmailArchiveFetchTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(EmailArchiveFetchTool.class);
    private final EmailArchivePlugin plugin;

    public EmailArchiveFetchTool(EmailArchivePlugin plugin) { this.plugin = plugin; }

    @Override public String getName() { return "email_archive_fetch"; }

    @Override public String getDescription() {
        return "Connect to IMAP server and archive emails to local .eml files.\n"
             + "Args: accountEmail (string, required) — the configured email account;\n"
             + "      days (integer, optional, default 30) — fetch emails from last N days;\n"
             + "      folder (string, optional, default INBOX) — IMAP folder;\n"
             + "      outputDir (string, optional) — local directory for .eml files.\n"
             + "Example: email_archive_fetch{\"accountEmail\":\"a@b.com\",\"days\":7}.";
    }

    @Override public boolean supportsLocal() { return false; }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("accountEmail", "string", "Configured email account address", true),
            AiToolParam.of("days", "integer", "Fetch emails from last N days (default 30)", false),
            AiToolParam.of("folder", "string", "IMAP folder name (default INBOX)", false),
            AiToolParam.of("outputDir", "string", "Local directory for .eml files", false)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String accountEmail = (String) args.get("accountEmail");
        if (accountEmail == null || accountEmail.isBlank())
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "accountEmail is required")));

        EmailArchiveConfig config = plugin.getConfig();
        config.setAccountEmail(accountEmail.trim());
        config.setDays(args.get("days") != null ? ((Number) args.get("days")).intValue() : 30);
        config.setImapFolder(args.get("folder") != null ? (String) args.get("folder") : "INBOX");
        if (args.get("outputDir") != null) {
            config.setOutputDir(Paths.get((String) args.get("outputDir")));
        }

        try {
            EmailArchiveService service = new EmailArchiveService();
            EmailArchiveService.ArchiveResult result = service.archive(config, null);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", result.errorMessage == null);
            out.put("summary", "Archived " + result.newArchived + " new (" + result.skippedDuplicates + " duplicates skipped)");
            out.put("totalFetched", result.totalFetched);
            out.put("newArchived", result.newArchived);
            out.put("skippedDuplicates", result.skippedDuplicates);
            out.put("errors", result.errors);
            out.put("outputDir", config.getOutputDir() != null
                    ? config.getOutputDir().toString() : ".zhiflow/email-archive/");
            if (result.errorMessage != null) out.put("error", result.errorMessage);

            log.info("email_archive_fetch: {} archived, {} skipped",
                    result.newArchived, result.skippedDuplicates);
            return AiToolResult.success(JsonHelper.toJson(out));
        } catch (Exception e) {
            log.error("email_archive_fetch error: {}", e.getMessage());
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "Archive failed: " + e.getMessage())));
        }
    }
}
