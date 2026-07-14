package fan.summer.fengyu.plugin.email.service;

import fan.summer.fengyu.plugin.email.model.EmailMessageRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BatchPlannerTest {
    @TempDir Path temp;

    @Test
    void plansOneMessagePerAttachmentTagWithIntersectionsAndCommonAttachments() throws Exception {
        Path eastReport = Files.writeString(temp.resolve("report_East.pdf"), "east");
        Path eastSupplement = Files.writeString(temp.resolve("supplement_East.xlsx"), "east2");
        Path southReport = Files.writeString(temp.resolve("report_South.pdf"), "south");
        Path ignored = Files.writeString(temp.resolve("README"), "ignored");
        Path commonTerms = temp.resolve("common-terms.pdf");

        var plan = BatchPlanner.byAttachmentTags(template(), temp, List.of(commonTerms), tag -> switch (tag) {
            case "East" -> new BatchPlanner.RecipientGroups(
                Set.of(" alice@example.com ", "bob@example.com"),
                Set.of("bob@example.com", "manager@example.com"));
            case "South" -> new BatchPlanner.RecipientGroups(Set.of("dana@example.com"), Set.of());
            default -> new BatchPlanner.RecipientGroups(Set.of(), Set.of());
        });

        assertEquals(List.of("East", "South"), plan.messages().stream().map(BatchPlanner.PlannedMessage::attachmentTag).toList());
        var east = plan.messages().getFirst();
        assertEquals(List.of("alice@example.com", "bob@example.com"), east.request().to());
        assertEquals(List.of("manager@example.com"), east.request().cc());
        assertEquals(List.of(eastReport, eastSupplement), east.tagAttachments());
        assertEquals(List.of(commonTerms), east.commonAttachments());
        assertEquals(List.of(eastReport, eastSupplement, commonTerms), east.request().attachments());
        assertEquals(List.of(ignored), plan.ignoredFiles());
        assertEquals(List.of(southReport, commonTerms), plan.messages().get(1).request().attachments());
    }

    @Test
    void parsesFinalUnderscoreSkipsTagsWithoutPrimaryRecipientsAndOrdersDeterministically() throws Exception {
        Path west = Files.writeString(temp.resolve("quarter_region_West.pdf"), "west");
        Path empty = Files.writeString(temp.resolve("quarter_Empty.pdf"), "empty");
        Files.writeString(temp.resolve("quarter_East.pdf"), "east");

        var plan = BatchPlanner.byAttachmentTags(template(), temp, List.of(), tag -> switch (tag) {
            case "East" -> new BatchPlanner.RecipientGroups(Set.of("b@example.com", "A@example.com", "a@example.com"), Set.of());
            case "West" -> new BatchPlanner.RecipientGroups(Set.of("west@example.com"), Set.of());
            default -> new BatchPlanner.RecipientGroups(Set.of(), Set.of("cc@example.com"));
        });

        assertEquals(List.of("East", "West"), plan.messages().stream().map(BatchPlanner.PlannedMessage::attachmentTag).toList());
        assertEquals(List.of("A@example.com", "b@example.com"), plan.messages().getFirst().request().to());
        assertEquals(List.of(west), plan.messages().get(1).tagAttachments());
        assertEquals(1, plan.skippedTags().size());
        assertEquals("Empty", plan.skippedTags().getFirst().attachmentTag());
        assertEquals("No primary recipients", plan.skippedTags().getFirst().reason());
        assertEquals(List.of(empty), plan.skippedTags().getFirst().attachments());
    }

    @Test
    void composeTagModeCreatesOnePrivateMessagePerPrimaryRecipientAndKeepsCommonFields() {
        var template = new EmailMessageRequest(7, List.of(), List.of("manager@example.com"),
            List.of("audit@example.com"), "Subject", "Plain", "<p>HTML</p>", List.of(temp.resolve("common.pdf")));

        var plan = BatchPlanner.byContactTags(template,
            Set.of("bob@example.com", " alice@example.com ", "alice@example.com"));

        assertEquals(List.of("alice@example.com", "bob@example.com"),
            plan.messages().stream().map(message -> message.request().to().getFirst()).toList());
        assertEquals(List.of("manager@example.com"), plan.messages().getFirst().request().cc());
        assertEquals(List.of("audit@example.com"), plan.messages().getFirst().request().bcc());
        assertEquals(template.attachments(), plan.messages().getFirst().request().attachments());
    }

    private static EmailMessageRequest template() {
        return new EmailMessageRequest(7, List.of(), List.of(), List.of(), "Subject", "Plain", "<p>HTML</p>", List.of());
    }
}
