package secondbrain.infrastructure.zendesk;

import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.zendesk.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ZenDeskClientMock implements ZenDeskClient {
    @Inject
    private OllamaClient ollamaClient;

    @Override
    public List<ZenDeskResultsResponse> getTickets(
            final Client client,
            final String authorization,
            final String url,
            final String query,
            final int ttlSeconds) {
        return generateMockTickets(3);
    }

    @Override
    public List<ZenDeskResultsResponse> getTickets(
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
            final String ticketId) {
        List<ZenDeskCommentResponse> comments = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            String comment = ollamaClient.callOllamaSimple("Generate a customer support comment for a ZenDesk ticket. Make it sound like a real customer support conversation.");
            comments.add(new ZenDeskCommentResponse(comment));
        }

        return new ZenDeskCommentsResponse(comments);
    }

    @Override
    public ZenDeskOrganizationItemResponse getOrganization(
            final Client client,
            final String authorization,
            final String url,
            final String orgId) {
        final String name = ollamaClient.callOllamaSimple("Generate a company or organization name. Return only the name, nothing else.");
        return new ZenDeskOrganizationItemResponse(name, orgId != null ? orgId : UUID.randomUUID().toString());
    }

    @Override
    public ZenDeskUserItemResponse getUser(
            final Client client,
            final String authorization,
            final String url,
            final String userId) {
        final String name = ollamaClient.callOllamaSimple("Generate a person's full name. Return only the name, nothing else.");
        return new ZenDeskUserItemResponse(name, userId != null ? userId : UUID.randomUUID().toString());
    }

    private List<ZenDeskResultsResponse> generateMockTickets(int count) {
        final List<ZenDeskResultsResponse> tickets = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            final String subject = ollamaClient.callOllamaSimple("Generate a subject line for a customer support ticket. Keep it concise.");
            final String description = ollamaClient.callOllamaSimple("Generate a detailed description for a customer support ticket. This should explain a customer's problem in detail.");
            final String email = ollamaClient.callOllamaSimple("Generate a random email address. Return only the email address, nothing else.");

            final String id = UUID.randomUUID().toString();
            final String submitterId = UUID.randomUUID().toString();
            final String organizationId = UUID.randomUUID().toString();

            tickets.add(new ZenDeskResultsResponse(
                    id,
                    subject,
                    description,
                    submitterId,
                    email,
                    organizationId
            ));
        }

        return tickets;
    }
}
