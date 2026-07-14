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

    @Test void plansOnePrivateMessagePerDistinctTagRecipient() {
        var template = template(List.of(temp.resolve("common.pdf")));

        var plan = BatchPlanner.byTags(template, Set.of("alice@example.com", "bob@example.com"));

        assertEquals(List.of("alice@example.com", "bob@example.com"),
            plan.messages().stream().map(message -> message.to().getFirst()).sorted().toList());
        assertEquals(1, plan.messages().getFirst().attachments().size());
        assertEquals(List.of(), plan.ignoredFiles());
    }

    @Test void groupsBySuffixAfterFinalUnderscoreAndIgnoresMalformedFiles() throws Exception {
        Path aliceOne = Files.writeString(temp.resolve("invoice_Q1_alice@example.com.pdf"), "a1");
        Path aliceTwo = Files.writeString(temp.resolve("invoice_Q2_alice@example.com.pdf"), "a2");
        Path bob = Files.writeString(temp.resolve("report_bob@example.com.txt"), "b");
        Path missingUnderscore = Files.writeString(temp.resolve("readme.pdf"), "ignored");
        Path missingExtension = Files.writeString(temp.resolve("report_carol"), "ignored");

        var plan = BatchPlanner.byFilename(template(List.of()), temp);

        assertEquals(List.of("alice@example.com", "bob@example.com"),
            plan.messages().stream().map(message -> message.to().getFirst()).sorted().toList());
        var alice = plan.messages().stream()
            .filter(message -> message.to().equals(List.of("alice@example.com"))).findFirst().orElseThrow();
        assertEquals(Set.of(aliceOne, aliceTwo), Set.copyOf(alice.attachments()));
        assertEquals(List.of(bob), plan.messages().stream()
            .filter(message -> message.to().equals(List.of("bob@example.com"))).findFirst().orElseThrow().attachments());
        assertEquals(Set.of(missingUnderscore, missingExtension), Set.copyOf(plan.ignoredFiles()));
    }

    private static EmailMessageRequest template(List<Path> attachments) {
        return new EmailMessageRequest(7, List.of(), List.of(), List.of(), "Subject",
            "Plain", "<p>HTML</p>", attachments);
    }
}
