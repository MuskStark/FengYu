package fan.summer.email.contract;

import fan.summer.fengyu.sdk.RpcContext;
import fan.summer.fengyu.sdk.contract.FengYuAiTool;
import fan.summer.fengyu.sdk.contract.FengYuContract;
import fan.summer.fengyu.sdk.contract.FengYuField;
import fan.summer.fengyu.sdk.contract.FengYuRpc;
import fan.summer.fengyu.sdk.contract.FengYuSensitive;
import java.util.List;

/** RPC contract for fan.summer.email — migrated from the manifest-first manifest.json. */
public interface EmailContract {
    @FengYuContract
    interface ConfirmationRpc {
    @FengYuRpc(name = "confirm_send", description = "Confirm and dispatch a previously prepared send by confirmation id.")
    @FengYuAiTool(description = "Dispatch a previously prepared send by confirmation id. Runs only with explicit approval: agents and visual workflows pause for a human go-ahead before this step executes.", effect = FengYuAiTool.ToolEffect.EXTERNAL)
    ConfirmSendOutput confirm_send(ConfirmSendInput input, RpcContext context);
    }

    @FengYuContract
    interface AccountRpc {
    @FengYuRpc(name = "email_account_delete", description = "Delete a configured account by id.")
    EmailAccountDeleteOutput email_account_delete(EmailAccountDeleteInput input, RpcContext context);

    @FengYuRpc(name = "email_account_find", description = "Find a configured account by id without returning credentials.")
    EmailAccountFindOutput email_account_find(EmailAccountFindInput input, RpcContext context);

    @FengYuRpc(name = "email_account_save", description = "Create or update an account. The password is accepted, encrypted at rest, and never returned.")
    EmailAccountSaveOutput email_account_save(EmailAccountSaveInput input, RpcContext context);

    @FengYuRpc(name = "email_account_set_default", description = "Mark a configured account as the default sender.")
    EmailAccountSetDefaultOutput email_account_set_default(EmailAccountSetDefaultInput input, RpcContext context);

    @FengYuRpc(name = "email_account_test", description = "Test the SMTP connection for a saved account without sending mail.")
    @FengYuAiTool(description = "Test the SMTP connection for a saved account without sending mail.", effect = FengYuAiTool.ToolEffect.EXTERNAL)
    EmailAccountTestOutput email_account_test(EmailAccountTestInput input, RpcContext context);

    @FengYuRpc(name = "email_account_test_imap", description = "Test the IMAP connection for a saved account without fetching mail.")
    @FengYuAiTool(description = "Test the IMAP connection for a saved account without fetching mail.", effect = FengYuAiTool.ToolEffect.EXTERNAL)
    EmailAccountTestImapOutput email_account_test_imap(EmailAccountTestImapInput input, RpcContext context);

    @FengYuRpc(name = "email_accounts_list", description = "List configured email accounts. Credentials are never returned.")
    @FengYuAiTool(description = "List configured email accounts without returning credentials.", effect = FengYuAiTool.ToolEffect.READ)
    EmailAccountsListOutput email_accounts_list(EmailAccountsListInput input, RpcContext context);
    }

    @FengYuContract
    interface ArchiveRpc {
    @FengYuRpc(name = "email_archive_detail", description = "Fetch the full metadata of a single archived message by id (bodies are not exposed).")
    EmailArchiveDetailOutput email_archive_detail(EmailArchiveDetailInput input, RpcContext context);

    @FengYuRpc(name = "email_archive_fetch", description = "Collect messages from an IMAP folder into an authorized output directory (synchronous).")
    @FengYuAiTool(description = "Collect messages from an IMAP folder into an authorized output directory.", effect = FengYuAiTool.ToolEffect.EXTERNAL)
    EmailArchiveFetchOutput email_archive_fetch(EmailArchiveFetchInput input, RpcContext context);

    @FengYuRpc(name = "email_archive_fetch_cancel", description = "Request cancellation of a running archive job by id (domain-level job cancel).")
    EmailArchiveFetchCancelOutput email_archive_fetch_cancel(EmailArchiveFetchCancelInput input, RpcContext context);

    @FengYuRpc(name = "email_archive_fetch_start", description = "Launch archive collection as a background job and return a jobId immediately; poll email_archive_fetch_status.")
    EmailArchiveFetchStartOutput email_archive_fetch_start(EmailArchiveFetchStartInput input, RpcContext context);

    @FengYuRpc(name = "email_archive_fetch_status", description = "Poll an archive job: drains streamed progress lines since the cursor and returns the final result when done.")
    EmailArchiveFetchStatusOutput email_archive_fetch_status(EmailArchiveFetchStatusInput input, RpcContext context);

    @FengYuRpc(name = "email_archive_query", description = "Search paginated archived message metadata without exposing message bodies.")
    @FengYuAiTool(description = "Search paginated archived email metadata without exposing message bodies.", effect = FengYuAiTool.ToolEffect.READ)
    EmailArchiveQueryOutput email_archive_query(EmailArchiveQueryInput input, RpcContext context);
    }

    @FengYuContract
    interface BatchConfigRpc {
    @FengYuRpc(name = "email_batch_preview", description = "Preview the per-recipient plan of a batch send without preparing or sending.")
    EmailBatchPreviewOutput email_batch_preview(EmailBatchPreviewInput input, RpcContext context);

    @FengYuRpc(name = "email_config_delete", description = "Delete a saved batch-send configuration template by id.")
    EmailConfigDeleteOutput email_config_delete(EmailConfigDeleteInput input, RpcContext context);

    @FengYuRpc(name = "email_config_find", description = "Find a saved batch-send configuration template by id.")
    EmailConfigFindOutput email_config_find(EmailConfigFindInput input, RpcContext context);

    @FengYuRpc(name = "email_config_save", description = "Create or update a named batch-send configuration template.")
    EmailConfigSaveOutput email_config_save(EmailConfigSaveInput input, RpcContext context);

    @FengYuRpc(name = "email_configs_list", description = "List saved batch-send configuration templates.")
    EmailConfigsListOutput email_configs_list(EmailConfigsListInput input, RpcContext context);
    }

    @FengYuContract
    interface ContactRpc {
    @FengYuRpc(name = "email_contact_delete", description = "Delete a contact by id.")
    EmailContactDeleteOutput email_contact_delete(EmailContactDeleteInput input, RpcContext context);

    @FengYuRpc(name = "email_contact_find", description = "Find a contact by id.")
    EmailContactFindOutput email_contact_find(EmailContactFindInput input, RpcContext context);

    @FengYuRpc(name = "email_contact_save", description = "Create or update a contact and assign its tags.")
    EmailContactSaveOutput email_contact_save(EmailContactSaveInput input, RpcContext context);

    @FengYuRpc(name = "email_contacts_import_commit", description = "Commit a contact import atomically using the same options as the preview.")
    EmailContactsImportCommitOutput email_contacts_import_commit(EmailContactsImportCommitInput input, RpcContext context);

    @FengYuRpc(name = "email_contacts_import_preview", description = "Dry-run a contact import: report what would be created/merged/skipped without writing.")
    EmailContactsImportPreviewOutput email_contacts_import_preview(EmailContactsImportPreviewInput input, RpcContext context);

    @FengYuRpc(name = "email_contacts_query", description = "Search contacts by text and/or tags for recipient planning.")
    @FengYuAiTool(description = "Search contacts and tags for recipient planning.", effect = FengYuAiTool.ToolEffect.READ)
    EmailContactsQueryOutput email_contacts_query(EmailContactsQueryInput input, RpcContext context);
    }

    @FengYuContract
    interface ImapRpc {
    @FengYuRpc(name = "email_imap_folders", description = "List the IMAP folders available on a saved account.")
    EmailImapFoldersOutput email_imap_folders(EmailImapFoldersInput input, RpcContext context);
    }

    @FengYuContract
    interface SendRpc {
    @FengYuRpc(name = "email_send_batch", description = "Prepare an attachment-matched batch send for confirmation without sending.")
    @FengYuAiTool(description = "Prepare attachment-matched batch messages for confirmation without sending.", effect = FengYuAiTool.ToolEffect.WRITE)
    EmailSendBatchOutput email_send_batch(EmailSendBatchInput input, RpcContext context);

    @FengYuRpc(name = "email_send_records_query", description = "Paginated query over send tasks and per-message delivery records.")
    EmailSendRecordsQueryOutput email_send_records_query(EmailSendRecordsQueryInput input, RpcContext context);

    @FengYuRpc(name = "email_send_single", description = "Prepare a direct or tag-selected message for confirmation. This call does not send.")
    @FengYuAiTool(description = "Prepare direct or tag-selected private messages for confirmation; this call does not send.", effect = FengYuAiTool.ToolEffect.WRITE)
    EmailSendSingleOutput email_send_single(EmailSendSingleInput input, RpcContext context);

    @FengYuRpc(name = "email_send_status", description = "Query a prepared or completed send by confirmation id.")
    @FengYuAiTool(description = "Query a prepared or completed send by confirmation ID.", effect = FengYuAiTool.ToolEffect.READ)
    EmailSendStatusOutput email_send_status(EmailSendStatusInput input, RpcContext context);
    }

    @FengYuContract
    interface TagRpc {
    @FengYuRpc(name = "email_tag_delete", description = "Delete a contact tag by id.")
    EmailTagDeleteOutput email_tag_delete(EmailTagDeleteInput input, RpcContext context);

    @FengYuRpc(name = "email_tag_save", description = "Create or rename a contact tag.")
    EmailTagSaveOutput email_tag_save(EmailTagSaveInput input, RpcContext context);

    @FengYuRpc(name = "email_tags_assign", description = "Bulk-assign a set of tags to a set of contacts.")
    EmailTagsAssignOutput email_tags_assign(EmailTagsAssignInput input, RpcContext context);

    @FengYuRpc(name = "email_tags_list", description = "List all contact tags.")
    EmailTagsListOutput email_tags_list(EmailTagsListInput input, RpcContext context);

    @FengYuRpc(name = "email_tags_resolve", description = "Resolve the de-duplicated recipient email addresses for a set of tags.")
    EmailTagsResolveOutput email_tags_resolve(EmailTagsResolveInput input, RpcContext context);
    }

    @FengYuContract
    interface RejectionRpc {
    @FengYuRpc(name = "reject_send", description = "Reject and discard a previously prepared send by confirmation id.")
    RejectSendOutput reject_send(RejectSendInput input, RpcContext context);
    }

    public record ConfirmSendInput(
        @FengYuField(required = true)
        String confirmationId
    ) {}

    public record ConfirmSendOutput(
        @FengYuField(required = true)
        ConfirmSendOutputSend send,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record ConfirmSendOutputSend(
          int failed,
          List<String> failedRecipients,
          @FengYuField(required = true)
          String status,
          int succeeded
      ) {}
    }

    public record EmailAccountDeleteInput(
        int id
    ) {}

    public record EmailAccountDeleteOutput(
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record EmailAccountFindInput(
        int id
    ) {}

    public record EmailAccountFindOutput(
        @FengYuField(required = true)
        EmailAccountFindOutputAccount account,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record EmailAccountFindOutputAccount(
          boolean defaultAccount,
          @FengYuField(required = true)
          String displayName,
          @FengYuField(required = true)
          String email,
          int id,
          String imapHost,
          Integer imapPort,
          String imapSecurity,
          boolean imapSkipCertVerify,
          boolean passwordConfigured,
          @FengYuField(required = true)
          String smtpHost,
          int smtpPort,
          @FengYuField(required = true)
          String smtpSecurity,
          boolean smtpSkipCertVerify
      ) {}
    }

    public record EmailAccountSaveInput(
        Boolean defaultAccount,
        String displayName,
        String email,
        @FengYuField(nullable = true)
        Integer id,
        String imapHost,
        @FengYuField(nullable = true)
        Integer imapPort,
        String imapSecurity,
        Boolean imapSkipCertVerify,
        @FengYuSensitive
        @FengYuField(description = "SMTP/IMAP credential secret. Scrubbed from all logs and responses.")
        String password,
        String smtpHost,
        Integer smtpPort,
        String smtpSecurity,
        Boolean smtpSkipCertVerify
    ) {}

    public record EmailAccountSaveOutput(
        @FengYuField(required = true)
        EmailAccountSaveOutputAccount account,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record EmailAccountSaveOutputAccount(
          boolean defaultAccount,
          @FengYuField(required = true)
          String displayName,
          @FengYuField(required = true)
          String email,
          int id,
          String imapHost,
          Integer imapPort,
          String imapSecurity,
          boolean imapSkipCertVerify,
          boolean passwordConfigured,
          @FengYuField(required = true)
          String smtpHost,
          int smtpPort,
          @FengYuField(required = true)
          String smtpSecurity,
          boolean smtpSkipCertVerify
      ) {}
    }

    public record EmailAccountSetDefaultInput(
        int id
    ) {}

    public record EmailAccountSetDefaultOutput(
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record EmailAccountTestInput(
        int accountId
    ) {}

    public record EmailAccountTestOutput(
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record EmailAccountTestImapInput(
        int accountId
    ) {}

    public record EmailAccountTestImapOutput(
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record EmailAccountsListInput() {}

    public record EmailAccountsListOutput(
        @FengYuField(required = true)
        List<EmailAccountsListOutputAccounts> accounts,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record EmailAccountsListOutputAccounts(
          boolean defaultAccount,
          @FengYuField(required = true)
          String displayName,
          @FengYuField(required = true)
          String email,
          int id,
          String imapHost,
          Integer imapPort,
          String imapSecurity,
          boolean imapSkipCertVerify,
          boolean passwordConfigured,
          @FengYuField(required = true)
          String smtpHost,
          int smtpPort,
          @FengYuField(required = true)
          String smtpSecurity,
          boolean smtpSkipCertVerify
      ) {}
    }

    public record EmailArchiveDetailInput(
        int id
    ) {}

    public record EmailArchiveDetailOutput(
        @FengYuField(required = true)
        EmailArchiveDetailOutputMessage message,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record EmailArchiveDetailOutputMessage(
          @FengYuField(required = true)
          String accountEmail,
          int accountId,
          String archivedAt,
          String bodyPreview,
          String emlPath,
          @FengYuField(required = true)
          String folder,
          String fromAddress,
          boolean hasAttachment,
          int id,
          String messageUid,
          String receivedAt,
          String recipientsJson,
          String sentAt,
          String subject
      ) {}
    }

    public record EmailArchiveFetchInput(
        int accountId,
        @FengYuField(description = "ISO-8601 inclusive end instant.")
        String end,
        @FengYuField(description = "IMAP folder name.", required = true)
        String folder,
        @FengYuField(description = "Resolved writable FengYu DirectoryRef for archived messages.", required = true, format = "fengyu-directory", fileAccess = "read-write")
        String outputDirectory,
        @FengYuField(description = "ISO-8601 inclusive start instant.")
        String start
    ) {}

    public record EmailArchiveFetchOutput(
        @FengYuField(required = true)
        EmailArchiveFetchOutputCollection collection,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record EmailArchiveFetchOutputCollection(
          int failures,
          int newArchived,
          int skippedDuplicates
      ) {}
    }

    public record EmailArchiveFetchCancelInput(
        @FengYuField(required = true)
        String jobId
    ) {}

    public record EmailArchiveFetchCancelOutput(
        String jobId,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record EmailArchiveFetchStartInput(
        int accountId,
        @FengYuField(description = "ISO-8601 inclusive end instant.")
        String end,
        @FengYuField(required = true)
        String folder,
        @FengYuField(description = "Resolved writable FengYu DirectoryRef for archived messages.", required = true, format = "fengyu-directory", fileAccess = "read-write")
        String outputDirectory,
        @FengYuField(description = "ISO-8601 inclusive start instant.")
        String start
    ) {}

    public record EmailArchiveFetchStartOutput(
        @FengYuField(required = true)
        String jobId,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record EmailArchiveFetchStatusInput(
        @FengYuField(description = "Absolute log cursor returned by the previous poll.", minimum = 0)
        Integer cursor,
        @FengYuField(required = true)
        String jobId
    ) {}

    public record EmailArchiveFetchStatusOutput(
        Integer cursor,
        Boolean done,
        Integer droppedLogs,
        Integer elapsedMs,
        String error,
        String jobId,
        List<String> logs,
        EmailArchiveFetchStatusOutputResult result,
        EmailArchiveFetchStatusOutputStatus status,
        boolean success,
        @FengYuField(required = true)
        String summary,
        String type
    ) {
      public record EmailArchiveFetchStatusOutputResult(
          int failures,
          int newArchived,
          int skippedDuplicates
      ) {}

      public enum EmailArchiveFetchStatusOutputStatus {
        RUNNING,
        DONE,
        FAILED,
        CANCELLED
      }
    }

    public record EmailArchiveQueryInput(
        @FengYuField(nullable = true)
        Integer accountId,
        @FengYuField(description = "ISO-8601 inclusive end instant.")
        String end,
        String folder,
        @FengYuField(minimum = 1, maximum = 100)
        Integer limit,
        @FengYuField(minimum = 0)
        Integer offset,
        String sender,
        @FengYuField(description = "ISO-8601 inclusive start instant.")
        String start,
        String subject
    ) {}

    public record EmailArchiveQueryOutput(
        @FengYuField(required = true)
        List<EmailArchiveQueryOutputMessages> messages,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record EmailArchiveQueryOutputMessages(
          @FengYuField(required = true)
          String accountEmail,
          int accountId,
          String archivedAt,
          String bodyPreview,
          String emlPath,
          @FengYuField(required = true)
          String folder,
          String fromAddress,
          boolean hasAttachment,
          int id,
          String messageUid,
          String receivedAt,
          String recipientsJson,
          String sentAt,
          String subject
      ) {}
    }

    public record EmailBatchPreviewInput(
        int accountId,
        @FengYuField(required = true)
        List<Integer> ccGroupTagIds,
        List<String> commonAttachments,
        String htmlText,
        @FengYuField(description = "Resolved readable FengYu DirectoryRef containing per-recipient attachment files.", required = true, format = "fengyu-directory", fileAccess = "read")
        String inputDirectory,
        String plainText,
        @FengYuField(required = true)
        List<Integer> recipientGroupTagIds,
        String subject
    ) {}

    public record EmailBatchPreviewOutput(
        @FengYuField(required = true)
        EmailBatchPreviewOutputPreview preview,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record EmailBatchPreviewOutputPreview(
          @FengYuField(required = true)
          List<String> ignoredFiles,
          int messageCount,
          @FengYuField(required = true)
          List<EmailBatchPreviewOutputPreviewMessages> messages,
          @FengYuField(required = true)
          List<EmailBatchPreviewOutputPreviewSkippedTags> skippedTags
      ) {
        public record EmailBatchPreviewOutputPreviewMessages(
            String attachmentTag,
            @FengYuField(required = true)
            List<String> cc,
            @FengYuField(required = true)
            List<String> commonAttachments,
            @FengYuField(required = true)
            List<String> tagAttachments,
            @FengYuField(required = true)
            List<String> to
        ) {}
      
        public record EmailBatchPreviewOutputPreviewSkippedTags(
            String attachmentTag,
            @FengYuField(required = true)
            List<String> attachments,
            @FengYuField(required = true)
            String reason
        ) {}
      }
    }

    public record EmailConfigDeleteInput(
        int id
    ) {}

    public record EmailConfigDeleteOutput(
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record EmailConfigFindInput(
        int id
    ) {}

    public record EmailConfigFindOutput(
        @FengYuField(required = true)
        EmailConfigFindOutputConfig config,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record EmailConfigFindOutputConfig(
          @FengYuField(required = true)
          String configJson,
          String createdAt,
          int id,
          @FengYuField(required = true)
          String mode,
          @FengYuField(required = true)
          String name
      ) {}
    }

    public record EmailConfigSaveInput(
        @FengYuField(description = "Template payload serialized as JSON.", required = true)
        String configJson,
        @FengYuField(nullable = true)
        Integer id,
        @FengYuField(required = true)
        String mode,
        @FengYuField(required = true)
        String name
    ) {}

    public record EmailConfigSaveOutput(
        @FengYuField(required = true)
        EmailConfigSaveOutputConfig config,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record EmailConfigSaveOutputConfig(
          @FengYuField(required = true)
          String configJson,
          String createdAt,
          int id,
          @FengYuField(required = true)
          String mode,
          @FengYuField(required = true)
          String name
      ) {}
    }

    public record EmailConfigsListInput() {}

    public record EmailConfigsListOutput(
        @FengYuField(required = true)
        List<EmailConfigsListOutputConfigs> configs,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record EmailConfigsListOutputConfigs(
          @FengYuField(required = true)
          String configJson,
          String createdAt,
          int id,
          @FengYuField(required = true)
          String mode,
          @FengYuField(required = true)
          String name
      ) {}
    }

    public record EmailContactDeleteInput(
        int id
    ) {}

    public record EmailContactDeleteOutput(
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record EmailContactFindInput(
        int id
    ) {}

    public record EmailContactFindOutput(
        @FengYuField(required = true)
        EmailContactFindOutputContact contact,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record EmailContactFindOutputContact(
          String createdAt,
          @FengYuField(required = true)
          String email,
          int id,
          String nickname,
          String notes,
          @FengYuField(required = true)
          List<Integer> tagIds
      ) {}
    }

    public record EmailContactSaveInput(
        String email,
        @FengYuField(nullable = true)
        Integer id,
        String nickname,
        String notes,
        List<Integer> tagIds
    ) {}

    public record EmailContactSaveOutput(
        @FengYuField(required = true)
        EmailContactSaveOutputContact contact,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record EmailContactSaveOutputContact(
          String createdAt,
          @FengYuField(required = true)
          String email,
          int id,
          String nickname,
          String notes,
          @FengYuField(required = true)
          List<Integer> tagIds
      ) {}
    }

    public record EmailContactsImportCommitInput(
        EmailContactsImportCommitInputDuplicateMode duplicateMode,
        @FengYuField(description = "Resolved readable FengYu FileRef for a CSV or Excel contact source.", required = true, format = "fengyu-file", fileAccess = "read")
        String sourceFile,
        String tagDelimiter
    ) {
      public enum EmailContactsImportCommitInputDuplicateMode {
        merge,
        skip,
        overwrite
      }
    }

    public record EmailContactsImportCommitOutput(
        @FengYuField(required = true)
        EmailContactsImportCommitOutputResult result,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record EmailContactsImportCommitOutputResult(
          int created,
          @FengYuField(required = true)
          List<EmailContactsImportCommitOutputResultErrors> errors,
          int merged,
          int skipped,
          int tagsAssigned,
          int tagsCreated
      ) {
        public record EmailContactsImportCommitOutputResultErrors(
            @FengYuField(required = true)
            String message,
            int row
        ) {}
      }
    }

    public record EmailContactsImportPreviewInput(
        @FengYuField(description = "How existing contacts are handled.")
        EmailContactsImportPreviewInputDuplicateMode duplicateMode,
        @FengYuField(description = "Resolved readable FengYu FileRef for a CSV or Excel contact source.", required = true, format = "fengyu-file", fileAccess = "read")
        String sourceFile,
        @FengYuField(description = "Tag delimiter for multi-value cells; 'auto' to sniff.")
        String tagDelimiter
    ) {
      public enum EmailContactsImportPreviewInputDuplicateMode {
        merge,
        skip,
        overwrite
      }
    }

    public record EmailContactsImportPreviewOutput(
        @FengYuField(required = true)
        EmailContactsImportPreviewOutputPreview preview,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record EmailContactsImportPreviewOutputPreview(
          int createdContacts,
          @FengYuField(required = true)
          List<String> createdTags,
          @FengYuField(required = true)
          List<EmailContactsImportPreviewOutputPreviewErrors> errors,
          int mergedContacts,
          int rowsTotal,
          int rowsValid,
          int skippedContacts
      ) {
        public record EmailContactsImportPreviewOutputPreviewErrors(
            @FengYuField(required = true)
            String message,
            int row
        ) {}
      }
    }

    public record EmailContactsQueryInput(
        @FengYuField(description = "Page size.", minimum = 1, maximum = 100)
        Integer limit,
        @FengYuField(description = "Page offset.", minimum = 0)
        Integer offset,
        @FengYuField(description = "Search text.")
        String query,
        @FengYuField(description = "Restrict to contacts tagged with any of these ids.")
        List<Integer> tagIds
    ) {}

    public record EmailContactsQueryOutput(
        @FengYuField(required = true)
        List<EmailContactsQueryOutputContacts> contacts,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record EmailContactsQueryOutputContacts(
          String createdAt,
          @FengYuField(required = true)
          String email,
          int id,
          String nickname,
          String notes,
          @FengYuField(required = true)
          List<Integer> tagIds
      ) {}
    }

    public record EmailImapFoldersInput(
        int accountId
    ) {}

    public record EmailImapFoldersOutput(
        @FengYuField(required = true)
        List<String> folders,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record EmailSendBatchInput(
        @FengYuField(description = "Sender account id.")
        int accountId,
        @FengYuField(description = "Tags whose members are CC'd on every message.", required = true)
        List<Integer> ccGroupTagIds,
        @FengYuField(description = "Resolved attachment paths attached to every message.")
        List<String> commonAttachments,
        String htmlText,
        @FengYuField(description = "Resolved readable FengYu DirectoryRef containing per-recipient attachment files.", required = true, format = "fengyu-directory", fileAccess = "read")
        String inputDirectory,
        String plainText,
        @FengYuField(description = "Tags whose members receive per-attachment messages.", required = true)
        List<Integer> recipientGroupTagIds,
        String subject
    ) {}

    public record EmailSendBatchOutput(
        @FengYuField(required = true)
        EmailSendBatchOutputConfirmation confirmation,
        Boolean confirmation_required,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record EmailSendBatchOutputConfirmation(
          @FengYuField(required = true)
          String approveMethod,
          @FengYuField(required = true)
          String confirmationId,
          @FengYuField(required = true)
          String expiresAt,
          @FengYuField(required = true)
          String pluginId,
          @FengYuField(required = true)
          String rejectMethod,
          @FengYuField(required = true)
          List<EmailSendBatchOutputConfirmationSummary> summary
      ) {
        public record EmailSendBatchOutputConfirmationSummary(
            String group,
            @FengYuField(required = true)
            String label,
            @FengYuField(required = true)
            String value
        ) {}
      }
    }

    public record EmailSendRecordsQueryInput(
        String confirmationId,
        @FengYuField(minimum = 1, maximum = 100)
        Integer limit,
        String messageStatus,
        @FengYuField(minimum = 0)
        Integer offset,
        String query,
        String taskStatus
    ) {}

    public record EmailSendRecordsQueryOutput(
        @FengYuField(required = true)
        List<EmailSendRecordsQueryOutputMessages> messages,
        boolean success,
        @FengYuField(required = true)
        String summary,
        @FengYuField(required = true)
        List<EmailSendRecordsQueryOutputTasks> tasks
    ) {
      public record EmailSendRecordsQueryOutputMessages(
          @FengYuField(required = true)
          String accountEmail,
          String attachmentJson,
          String confirmationId,
          String errorMessage,
          int id,
          String recipientsJson,
          String sentAt,
          @FengYuField(required = true)
          String status,
          String subject
      ) {}

      public record EmailSendRecordsQueryOutputTasks(
          int accountId,
          @FengYuField(required = true)
          String confirmationId,
          String expiresAt,
          @FengYuField(required = true)
          String mode,
          @FengYuField(required = true)
          String status,
          String updatedAt
      ) {}
    }

    public record EmailSendSingleInput(
        @FengYuField(description = "Sender account id.")
        int accountId,
        @FengYuField(description = "Resolved attachment file paths.")
        List<String> attachments,
        List<String> bcc,
        List<String> cc,
        @FengYuField(description = "HTML body (sanitized server-side).")
        String htmlText,
        @FengYuField(description = "Plain-text body.")
        String plainText,
        @FengYuField(description = "Resolve recipients from these tags.")
        List<Integer> recipientTagIds,
        String subject,
        @FengYuField(description = "Direct recipient email addresses.")
        List<String> to
    ) {}

    public record EmailSendSingleOutput(
        @FengYuField(required = true)
        EmailSendSingleOutputConfirmation confirmation,
        Boolean confirmation_required,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record EmailSendSingleOutputConfirmation(
          @FengYuField(required = true)
          String approveMethod,
          @FengYuField(required = true)
          String confirmationId,
          @FengYuField(required = true)
          String expiresAt,
          @FengYuField(required = true)
          String pluginId,
          @FengYuField(required = true)
          String rejectMethod,
          @FengYuField(required = true)
          List<EmailSendSingleOutputConfirmationSummary> summary
      ) {
        public record EmailSendSingleOutputConfirmationSummary(
            String group,
            @FengYuField(required = true)
            String label,
            @FengYuField(required = true)
            String value
        ) {}
      }
    }

    public record EmailSendStatusInput(
        @FengYuField(required = true)
        String confirmationId
    ) {}

    public record EmailSendStatusOutput(
        @FengYuField(required = true)
        EmailSendStatusOutputSend send,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record EmailSendStatusOutputSend(
          int accountId,
          @FengYuField(required = true)
          String confirmationId,
          String expiresAt,
          @FengYuField(required = true)
          String mode,
          @FengYuField(required = true)
          String status,
          String updatedAt
      ) {}
    }

    public record EmailTagDeleteInput(
        int id
    ) {}

    public record EmailTagDeleteOutput(
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record EmailTagSaveInput(
        @FengYuField(nullable = true)
        Integer id,
        String name
    ) {}

    public record EmailTagSaveOutput(
        boolean success,
        @FengYuField(required = true)
        String summary,
        @FengYuField(required = true)
        EmailTagSaveOutputTag tag
    ) {
      public record EmailTagSaveOutputTag(
          int id,
          @FengYuField(required = true)
          String name
      ) {}
    }

    public record EmailTagsAssignInput(
        List<Integer> contactIds,
        List<Integer> tagIds
    ) {}

    public record EmailTagsAssignOutput(
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record EmailTagsListInput() {}

    public record EmailTagsListOutput(
        boolean success,
        @FengYuField(required = true)
        String summary,
        @FengYuField(required = true)
        List<EmailTagsListOutputTags> tags
    ) {
      public record EmailTagsListOutputTags(
          int id,
          @FengYuField(required = true)
          String name
      ) {}
    }

    public record EmailTagsResolveInput(
        List<Integer> tagIds
    ) {}

    public record EmailTagsResolveOutput(
        @FengYuField(required = true)
        List<String> recipients,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record RejectSendInput(
        @FengYuField(required = true)
        String confirmationId
    ) {}

    public record RejectSendOutput(
        @FengYuField(required = true)
        RejectSendOutputSend send,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record RejectSendOutputSend(
          int failed,
          List<String> failedRecipients,
          @FengYuField(required = true)
          String status,
          int succeeded
      ) {}
    }

}
