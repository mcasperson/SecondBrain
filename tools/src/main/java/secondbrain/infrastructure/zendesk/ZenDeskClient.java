package secondbrain.infrastructure.zendesk;

import jakarta.ws.rs.client.Client;
import secondbrain.infrastructure.zendesk.api.ZenDeskCommentsResponse;
import secondbrain.infrastructure.zendesk.api.ZenDeskOrganizationItemResponse;
import secondbrain.infrastructure.zendesk.api.ZenDeskTicket;
import secondbrain.infrastructure.zendesk.api.ZenDeskUserItemResponse;

import java.util.List;

public interface ZenDeskClient {
    List<ZenDeskTicket> getTickets(
            Client client,
            String authorization,
            String url,
            String query,
            int ttlSeconds);

    ZenDeskTicket getTicket(
            Client client,
            String authorization,
            String url,
            String ticketId,
            int ttlSeconds);

    ZenDeskCommentsResponse getComments(
            Client client,
            String authorization,
            String url,
            String ticketId,
            int ttlSeconds);

    ZenDeskOrganizationItemResponse getOrganization(
            Client client,
            String authorization,
            String url,
            String orgId);

    ZenDeskUserItemResponse getUser(
            Client client,
            String authorization,
            String url,
            String userId);
}
