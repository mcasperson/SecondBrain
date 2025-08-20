package secondbrain.infrastructure.zendesk;

import secondbrain.infrastructure.zendesk.api.ZenDeskCommentsResponse;
import secondbrain.infrastructure.zendesk.api.ZenDeskOrganizationItemResponse;
import secondbrain.infrastructure.zendesk.api.ZenDeskTicket;
import secondbrain.infrastructure.zendesk.api.ZenDeskUserItemResponse;

import java.util.List;

public interface ZenDeskClient {
    List<ZenDeskTicket> getTickets(
            String authorization,
            String url,
            String query,
            int ttlSeconds);

    ZenDeskTicket getTicket(
            String authorization,
            String url,
            String ticketId,
            int ttlSeconds);

    ZenDeskCommentsResponse getComments(
            String authorization,
            String url,
            String ticketId,
            int ttlSeconds);

    ZenDeskOrganizationItemResponse getOrganization(
            String authorization,
            String url,
            String orgId);

    ZenDeskUserItemResponse getUser(
            String authorization,
            String url,
            String userId);
}
