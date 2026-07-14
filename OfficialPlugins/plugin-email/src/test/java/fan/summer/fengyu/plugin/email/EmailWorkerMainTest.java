package fan.summer.fengyu.plugin.email;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import fan.summer.fengyu.plugin.email.crypto.CredentialCipher;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.rpc.EmailRpcHandlers;
import fan.summer.fengyu.plugin.email.service.AccountService;
import fan.summer.fengyu.plugin.email.service.AddressBookService;
import fan.summer.fengyu.sdk.PluginDatabaseConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.KeyGenerator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailWorkerMainTest {
    private static final Gson JSON = new Gson();
    @TempDir Path temp;

    @Test void roundTripsAllSevenAiMethodsAndConfirmationMethodsWithCleanStdout() throws Exception {
        EmailDatabase database = database();
        CredentialCipher cipher = cipher();
        long accountId = new AccountService(database, cipher).save(new AccountService.AccountInput(null,
            "Sender", "sender@example.com", "secret", "127.0.0.1", 2525, "PLAIN",
            null, null, null, true));
        AddressBookService addressBook = new AddressBookService(database);
        long tagId = addressBook.saveTag(null, "customers");
        long attachmentTagId = addressBook.saveTag(null, "East");
        long contactId = addressBook.saveContact(new AddressBookService.ContactInput(null,
            "recipient@example.com", "Recipient"));
        addressBook.assignTags(Set.of(contactId), Set.of(tagId, attachmentTagId));
        Path batchDirectory = Files.createDirectory(temp.resolve("worker-batch"));
        Files.writeString(batchDirectory.resolve("report_East.pdf"), "report");

        List<Request> requests = new ArrayList<>();
        requests.add(request(1, "email_accounts_list", Map.of()));
        requests.add(request(2, "email_contacts_query", Map.of("query", "recipient", "limit", 20)));
        requests.add(request(3, "email_send_single", message(accountId)));
        requests.add(request(4, "email_send_batch", batch(accountId, tagId, batchDirectory)));
        requests.add(request(5, "email_send_status", Map.of("confirmationId", "missing")));
        requests.add(request(6, "email_archive_fetch", Map.of(
            "accountId", accountId + 100, "folder", "INBOX", "outputDirectory", temp.toString())));
        requests.add(request(7, "email_archive_query", Map.of("limit", 20)));

        String input = requests.stream().map(Request::json).reduce("", (left, right) -> left + right + "\n");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EmailWorkerMain.worker(new EmailRpcHandlers(database, cipher)).run(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);

        List<Map<String, Object>> responses = output.toString(StandardCharsets.UTF_8).lines()
            .map(EmailWorkerMainTest::object).toList();
        assertEquals(requests.size(), responses.size(), "stdout must contain exactly one response per request");
        for (int index = 0; index < responses.size(); index++) {
            Map<String, Object> response = responses.get(index);
            assertEquals((double) (index + 1), response.get("id"));
            assertFalse(response.containsKey("error"), response::toString);
            Map<String, Object> result = castMap(response.get("result"));
            assertNotNull(result.get("success"), result::toString);
            assertTrue(result.get("summary") instanceof String, result::toString);
        }

        Map<String, Object> single = result(responses.get(2));
        Map<String, Object> batch = result(responses.get(3));
        assertEquals(true, single.get("confirmation_required"));
        assertEquals(true, batch.get("confirmation_required"));
        assertNotNull(single.get("confirmation"));
        assertNotNull(batch.get("confirmation"));
    }

    @Test void registersAccountContactTagConfigSendAndArchiveUiOperations() throws Exception {
        EmailDatabase database = database();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<String> methods = List.of(
            "email_account_save", "email_account_find", "email_account_delete", "email_account_set_default",
            "email_account_test", "email_contact_save", "email_contact_find", "email_contact_delete",
            "email_tag_save", "email_tags_list", "email_tag_delete", "email_tags_assign",
            "email_config_save", "email_configs_list", "email_config_find", "email_config_delete",
            "email_batch_preview", "email_send_records_query", "email_archive_detail", "confirm_send", "reject_send");
        String input = methods.stream().map(method -> request(methods.indexOf(method) + 1, method, Map.of()).json())
            .reduce("", (left, right) -> left + right + "\n");

        EmailWorkerMain.worker(new EmailRpcHandlers(database, cipher())).run(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);

        List<Map<String, Object>> responses = output.toString(StandardCharsets.UTF_8).lines()
            .map(EmailWorkerMainTest::object).toList();
        assertEquals(methods.size(), responses.size());
        assertTrue(responses.stream().noneMatch(response -> {
            Object error = response.get("error");
            return error instanceof Map<?, ?> map && ((Number) map.get("code")).intValue() == -32601;
        }), responses::toString);
    }

    @Test void missingMutationTargetsReturnFailureEnvelopes() throws Exception {
        EmailRpcHandlers handlers = new EmailRpcHandlers(database(), cipher());
        List<Object> results = List.of(
            handlers.setDefaultAccount(Map.of("id", 999)),
            handlers.deleteAccount(Map.of("id", 999)),
            handlers.deleteContact(Map.of("id", 999)),
            handlers.deleteTag(Map.of("id", 999)),
            handlers.deleteConfig(Map.of("id", 999)));

        for (Object value : results) {
            assertEquals(false, castMap(value).get("success"), value::toString);
        }
    }

    private EmailDatabase database() {
        return new EmailDatabase(new PluginDatabaseConfig("h2", "org.h2.Driver",
            "jdbc:h2:mem:worker-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "", temp));
    }

    private static CredentialCipher cipher() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return new CredentialCipher(generator.generateKey());
    }

    private static Map<String, Object> message(long accountId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("accountId", accountId);
        values.put("to", List.of("recipient@example.com"));
        values.put("cc", List.of());
        values.put("bcc", List.of());
        values.put("subject", "Hello");
        values.put("plainText", "Body");
        values.put("attachments", List.of());
        return values;
    }

    private static Map<String, Object> batch(long accountId, long tagId, Path directory) {
        Map<String, Object> values = new LinkedHashMap<>(message(accountId));
        values.remove("to");
        values.put("recipientGroupTagIds", List.of(tagId));
        values.put("ccGroupTagIds", List.of());
        values.put("inputDirectory", directory.toString());
        values.put("commonAttachments", List.of());
        return values;
    }

    private static Request request(int id, String method, Map<String, Object> params) {
        return new Request(JSON.toJson(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params)));
    }

    private static Map<String, Object> object(String value) {
        return JSON.fromJson(value, new TypeToken<Map<String, Object>>() { }.getType());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) { return (Map<String, Object>) value; }
    private static Map<String, Object> result(Map<String, Object> response) { return castMap(response.get("result")); }
    private record Request(String json) { }
}
