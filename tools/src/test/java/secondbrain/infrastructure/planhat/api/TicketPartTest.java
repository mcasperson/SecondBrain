package secondbrain.infrastructure.planhat.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TicketPartTest {
    private static final EmailAttachment ATTACHMENT = new EmailAttachment("a1", "log.txt", "text/plain", 12);

    private static TicketPart full() {
        return new TicketPart("part-1", "conv-1", "ext-ticket-1", "ext-1", "comment", "email", "The deployment failed",
                "2025-01-02", "Jane", "user-1", "enduser-1", "assignee-1", "zendesk", true, 3, List.of("week"),
                List.of(ATTACHMENT), "company-1", "https://app.planhat.com");
    }

    private static TicketPart empty() {
        return new TicketPart(null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    @Test
    void gettersReturnTheFieldValues() {
        final TicketPart part = full();

        assertEquals("part-1", part.getId());
        assertEquals("conv-1", part.getConversationId());
        assertEquals("ext-ticket-1", part.getExternalTicketId());
        assertEquals("ext-1", part.getExternalId());
        assertEquals("comment", part.getType());
        assertEquals("email", part.getVia());
        assertEquals("The deployment failed", part.getBody());
        assertEquals("2025-01-02", part.getCreateDate());
        assertEquals("Jane", part.getAuthorName());
        assertEquals("user-1", part.getAuthorUserId());
        assertEquals("enduser-1", part.getAuthorEnduserId());
        assertEquals("assignee-1", part.getAssigneeUserId());
        assertEquals("zendesk", part.getSource());
        assertTrue(part.getIsPrivate());
        assertEquals(3, part.getDays());
        assertEquals(List.of("week"), part.getTimeBucket());
        assertEquals(List.of(ATTACHMENT), part.getAttachments());
        assertEquals("company-1", part.getCompanyId());
        assertEquals("https://app.planhat.com", part.getUrl());
    }

    @Test
    void gettersReturnDefaultsForNullFields() {
        final TicketPart part = empty();

        assertEquals("", part.getId());
        assertEquals("", part.getConversationId());
        assertEquals("", part.getExternalTicketId());
        assertEquals("", part.getExternalId());
        assertEquals("", part.getType());
        assertEquals("", part.getVia());
        assertEquals("", part.getBody());
        assertEquals("", part.getCreateDate());
        assertEquals("", part.getAuthorName());
        assertEquals("", part.getAuthorUserId());
        assertEquals("", part.getAuthorEnduserId());
        assertEquals("", part.getAssigneeUserId());
        assertEquals("", part.getSource());
        assertFalse(part.getIsPrivate());
        assertEquals(0, part.getDays());
        assertEquals(List.of(), part.getTimeBucket());
        assertEquals(List.of(), part.getAttachments());
        assertEquals("", part.getCompanyId());
        assertEquals("", part.getUrl());
    }

    @Test
    void getIsPrivateReturnsFalseForPublicComments() {
        final TicketPart part = new TicketPart("p", null, null, null, null, null, null, null, null, null, null, null, null,
                false, null, null, null, null, null);

        assertFalse(part.getIsPrivate());
    }

    @Test
    void generatedValuesUseTheIdBodyAndConversationUrl() {
        final TicketPart part = full();

        assertEquals("part-1", part.generateId());
        assertEquals("The deployment failed", part.generateText());
        assertEquals("Planhat Ticket", part.generateLinkText());
        assertEquals("https://app.planhat.com/profile/company-1?conversationId=conv-1", part.generateUrl());
    }

    @Test
    void generatedValuesUseEmptyStringsForNullFields() {
        final TicketPart part = empty();

        assertEquals("", part.generateId());
        assertEquals("", part.generateText());
        assertEquals("/profile/?conversationId=", part.generateUrl());
    }

    @Test
    void updateBodyReturnsACopyWithOnlyTheBodyChanged() {
        final TicketPart original = full();

        final TicketPart updated = original.updateBody("Redacted");

        assertEquals("Redacted", updated.body());
        assertEquals("The deployment failed", original.body());
        assertEquals(new TicketPart("part-1", "conv-1", "ext-ticket-1", "ext-1", "comment", "email", "Redacted",
                "2025-01-02", "Jane", "user-1", "enduser-1", "assignee-1", "zendesk", true, 3, List.of("week"),
                List.of(ATTACHMENT), "company-1", "https://app.planhat.com"), updated);
    }

    @Test
    void updateUrlReturnsACopyWithTheUrlAndCompanyChanged() {
        final TicketPart original = empty().updateBody("body");

        final TicketPart updated = original.updateUrl("https://example.planhat.com", "company-2");

        assertEquals("https://example.planhat.com", updated.url());
        assertEquals("company-2", updated.companyId());
        assertEquals("body", updated.body());
        assertNull(original.url());
        assertNull(original.companyId());
        assertEquals("https://example.planhat.com/profile/company-2?conversationId=", updated.generateUrl());
    }

    @Test
    void deserializesTheIdAndPrivateFlagAndIgnoresUnknownFields() throws JsonProcessingException {
        final String json = """
                {"_id": "part-9", "conversationId": "conv-9", "body": "Note", "isPrivate": true,
                "attachments": [{"id": "a9", "fileName": "f.txt", "mimeType": "text/plain", "size": 4}], "unknown": "x"}
                """;

        final TicketPart part = new ObjectMapper().readValue(json, TicketPart.class);

        assertEquals("part-9", part.getId());
        assertEquals("conv-9", part.getConversationId());
        assertEquals("Note", part.getBody());
        assertTrue(part.getIsPrivate());
        assertEquals(List.of(new EmailAttachment("a9", "f.txt", "text/plain", 4)), part.getAttachments());
        assertEquals("", part.getUrl());
    }
}
