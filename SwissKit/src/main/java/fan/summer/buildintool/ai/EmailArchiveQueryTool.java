package fan.summer.buildintool.ai;

import fan.summer.zhiflow.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import fan.summer.buildintool.emailarchive.*;
import fan.summer.database.entity.email.EmailArchiveEntity;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class EmailArchiveQueryTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(EmailArchiveQueryTool.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    @Override public String getName() { return "email_archive_query"; }

    @Override public String getDescription() {
        return "Search archived emails in the local database. All parameters are optional.\n"
             + "Args: accountEmail (string) — filter by account;\n"
             + "      fromAddress (string) — filter by sender (partial match);\n"
             + "      subject (string) — filter by subject (partial match);\n"
             + "      startDate (string) — ISO date like 2026-01-01;\n"
             + "      endDate (string) — ISO date like 2026-05-28;\n"
             + "      limit (integer, default 20) — max results.\n"
             + "Example: email_archive_query{\"subject\":\"invoice\",\"limit\":10}.";
    }

    @Override public String getLocalDescription() {
        return "Search archived emails. Args: subject (string), fromAddress (string), "
             + "startDate (ISO), endDate (ISO), limit (integer).\n"
             + "Example: email_archive_query{\"subject\":\"invoice\"}.";
    }

    @Override public List<AiToolParam> getLocalParameters() {
        // Qwen3-friendly subset: drop accountEmail filtering (rarely useful for the 4B model)
        return List.of(
            AiToolParam.of("subject",     "string",  "Filter by subject (partial match)", false),
            AiToolParam.of("fromAddress", "string",  "Filter by sender (partial match)",  false),
            AiToolParam.of("startDate",   "string",  "ISO date (e.g. 2026-01-01)",        false),
            AiToolParam.of("endDate",     "string",  "ISO date (e.g. 2026-05-28)",        false),
            AiToolParam.of("limit",       "integer", "Max results (default 20)",          false)
        );
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("accountEmail", "string", "Filter by account email", false),
            AiToolParam.of("fromAddress", "string", "Filter by sender (partial match)", false),
            AiToolParam.of("subject", "string", "Filter by subject (partial match)", false),
            AiToolParam.of("startDate", "string", "Start date (ISO: 2026-01-01)", false),
            AiToolParam.of("endDate", "string", "End date (ISO: 2026-05-28)", false),
            AiToolParam.of("limit", "integer", "Max results (default 20, max 100)", false)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String accountEmail = (String) args.get("accountEmail");
        String fromAddress = (String) args.get("fromAddress");
        String subject = (String) args.get("subject");

        Date startDate = null, endDate = null;
        if (args.get("startDate") != null) {
            startDate = Date.valueOf(LocalDate.parse((String) args.get("startDate"), ISO));
        }
        if (args.get("endDate") != null) {
            endDate = Date.valueOf(LocalDate.parse((String) args.get("endDate"), ISO));
        }

        try {
            EmailArchiveService service = new EmailArchiveService();
            List<EmailArchiveEntity> results =
                    service.query(accountEmail, fromAddress, subject, startDate, endDate);

            int limit = args.get("limit") != null ? Math.min(((Number) args.get("limit")).intValue(), 100) : 20;
            if (results.size() > limit) results = results.subList(0, limit);

            List<Map<String, Object>> emails = new ArrayList<>();
            for (EmailArchiveEntity e : results) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("subject", e.getSubject());
                map.put("from", e.getFromAddress());
                map.put("to", e.getToAddress());
                map.put("date", e.getSendDate() != null ? e.getSendDate().toString() : null);
                map.put("hasAttachment", e.getHasAttachment());
                map.put("preview", e.getBodyPreview());
                map.put("folder", e.getFolder());
                emails.add(map);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("summary", "Found " + emails.size() + " email(s)");
            out.put("totalResults", emails.size());
            out.put("emails", emails);

            log.info("email_archive_query: {} results", emails.size());
            return AiToolResult.success(JsonHelper.toJson(out));
        } catch (Exception e) {
            log.error("email_archive_query error: {}", e.getMessage());
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "Query failed: " + e.getMessage())));
        }
    }
}
