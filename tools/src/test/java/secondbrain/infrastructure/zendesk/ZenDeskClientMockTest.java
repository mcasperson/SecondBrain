package secondbrain.infrastructure.zendesk;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.zendesk.api.*;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(ZenDeskClientMock.class)
class ZenDeskClientMockTest {
    private static final String AUTH = "auth";
    private static final String URL = "https://example.zendesk.com";

    private final AtomicInteger llmCalls = new AtomicInteger();
    private final RecordingLlmClient llmClient = new RecordingLlmClient()
            .respondWith(document -> "llm-" + llmCalls.incrementAndGet());

    @Inject
    private ZenDeskClientMock client;

    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return llmClient;
    }

    private String prompt(final int index) {
        return llmClient.requests.get(index).document().getPrompts().get(0);
    }

    private static void assertUuid(@Nullable final String value) {
        assertNotNull(value);
        assertEquals(value, UUID.fromString(value).toString());
    }

    @Test
    void anyItemsInDurationIsAlwaysTrue() {
        assertTrue(client.anyItemsInDuration(AUTH, URL, "query", ChronoUnit.DAYS, ChronoUnit.HOURS));
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void getTicketsGeneratesThreeTicketsWithLlmSubjectAndEmail() {
        final List<ZenDeskTicket> tickets = client.getTickets(AUTH, URL, "query", 60);

        assertEquals(3, tickets.size());
        assertEquals(List.of("llm-1", "llm-3", "llm-5"), tickets.stream().map(ZenDeskTicket::subject).toList());
        assertEquals(List.of("llm-2", "llm-4", "llm-6"), tickets.stream().map(ZenDeskTicket::recipient).toList());
        assertEquals(6, llmClient.requests.size());
        assertTrue(prompt(0).contains("subject line"), prompt(0));
        assertTrue(prompt(1).contains("email address"), prompt(1));

        for (final ZenDeskTicket ticket : tickets) {
            assertUuid(ticket.id());
            assertUuid(ticket.submitterId());
            assertUuid(ticket.assigneeId());
            assertUuid(ticket.organizationId());
            assertNull(ticket.comments());
            assertNull(ticket.url());
            assertNull(ticket.status());
            assertNull(ticket.createdAt());
        }

        final Set<String> ids = tickets.stream().map(ZenDeskTicket::id).collect(Collectors.toSet());
        assertEquals(3, ids.size());
    }

    @Test
    void getTicketKeepsRequestedIdAndDoesNotCallLlm() {
        final ZenDeskTicket ticket = client.getTicket(AUTH, URL, "ticket-42", 60);

        assertEquals("ticket-42", ticket.id());
        assertUuid(ticket.submitterId());
        assertUuid(ticket.assigneeId());
        assertUuid(ticket.subject());
        assertUuid(ticket.organizationId());
        assertUuid(ticket.recipient());
        assertUuid(ticket.comments());
        assertNull(ticket.url());
        assertNull(ticket.status());
        assertNull(ticket.createdAt());
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void getCommentsGeneratesThreeLlmComments() {
        final ZenDeskCommentsResponse response = client.getComments(AUTH, URL, "ticket-1", 60);

        assertEquals(List.of("llm-1", "llm-2", "llm-3"), response.comments().stream().map(ZenDeskCommentResponse::body).toList());
        response.comments().forEach(comment -> {
            assertNotNull(comment.author_id());
            assertEquals("", comment.created_at());
        });
        assertTrue(prompt(0).contains("customer support comment"), prompt(0));
    }

    @Test
    void getOrganizationUsesLlmNameAndRequestedId() {
        final ZenDeskOrganizationItemResponse organization = client.getOrganization(AUTH, URL, "org-7");

        assertEquals(new ZenDeskOrganizationItemResponse("llm-1", "org-7"), organization);
        assertTrue(prompt(0).contains("organization name"), prompt(0));
    }

    @Test
    @SuppressWarnings("NullAway")
    void getOrganizationGeneratesIdWhenNoneIsRequested() {
        final ZenDeskOrganizationItemResponse organization = client.getOrganization(AUTH, URL, null);

        assertEquals("llm-1", organization.name());
        assertUuid(organization.id());
    }

    @Test
    void getUserUsesLlmNameRequestedIdAndFixedEmail() {
        final ZenDeskUserItemResponse user = client.getUser(AUTH, URL, "user-9");

        assertEquals(new ZenDeskUserItemResponse("llm-1", "user-9", "user@example.org", 1L), user);
        assertTrue(prompt(0).contains("full name"), prompt(0));
    }

    @Test
    @SuppressWarnings("NullAway")
    void getUserGeneratesIdWhenNoneIsRequested() {
        final ZenDeskUserItemResponse user = client.getUser(AUTH, URL, null);

        assertEquals("llm-1", user.name());
        assertUuid(user.id());
    }
}
