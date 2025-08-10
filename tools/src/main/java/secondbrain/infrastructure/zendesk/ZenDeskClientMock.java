package secondbrain.infrastructure.zendesk;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.zendesk.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@ApplicationScoped
public class ZenDeskClientMock implements ZenDeskClient {
    @Inject
    private LlmClient llmClient;

    @Override
    public List<ZenDeskTicket> getTickets(
            final Client client,
            final String authorization,
            final String url,
            final String query,
            final int ttlSeconds) {
        return generateMockTickets(3);
    }

    @Override
    public ZenDeskTicket getTicket(Client client, String authorization, String url, String ticketId, int ttlSeconds) {
        return new ZenDeskTicket(
                ticketId,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString()
        );
    }

    private List<ZenDeskTicket> getTickets(
            final Client client,
            final String authorization,
            final String url,
            final String query,
            final int page,
            final int maxPage,
            final int ttlSeconds) {
        return generateMockTickets(page < maxPage ? 5 : 2);
    }

    @Override
    public ZenDeskCommentsResponse getComments(
            final Client client,
            final String authorization,
            final String url,
            final String ticketId,
            final int ttlSeconds) {
        List<ZenDeskCommentResponse> comments = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            String comment = llmClient.call("Generate a customer support comment for a ZenDesk ticket. Make it sound like a real customer support conversation.");
            comments.add(new ZenDeskCommentResponse(comment, new Random().nextLong(), ""));
        }

        return new ZenDeskCommentsResponse(comments);
    }

    @Override
    public ZenDeskOrganizationItemResponse getOrganization(
            final Client client,
            final String authorization,
            final String url,
            final String orgId) {
        final String name = llmClient.call("Generate a company or organization name. Return only the name, nothing else.");
        return new ZenDeskOrganizationItemResponse(name, orgId != null ? orgId : UUID.randomUUID().toString());
    }

    @Override
    public ZenDeskUserItemResponse getUser(
            final Client client,
            final String authorization,
            final String url,
            final String userId) {
        final String name = llmClient.call("Generate a person's full name. Return only the name, nothing else.");
        return new ZenDeskUserItemResponse(name, userId != null ? userId : UUID.randomUUID().toString(), "user@example.org", 1L);
    }

    private List<ZenDeskTicket> generateMockTickets(int count) {
        final List<ZenDeskTicket> tickets = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            final String subject = llmClient.call("Generate a subject line for a customer support ticket. Keep it concise.");
            final String email = llmClient.call("Generate a random email address. Return only the email address, nothing else.");

            final String id = UUID.randomUUID().toString();
            final String submitterId = UUID.randomUUID().toString();
            final String assigneeId = UUID.randomUUID().toString();
            final String organizationId = UUID.randomUUID().toString();

            tickets.add(new ZenDeskTicket(
                    id,
                    submitterId,
                    assigneeId,
                    subject,
                    organizationId,
                    email
            ));
        }

        return tickets;
    }
}
