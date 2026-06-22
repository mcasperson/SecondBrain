package secondbrain.infrastructure.salesforce;

import jakarta.enterprise.context.ApplicationScoped;
import secondbrain.infrastructure.salesforce.api.SalesforceEmailRecord;
import secondbrain.infrastructure.salesforce.api.SalesforceOauthTokenResponse;
import secondbrain.infrastructure.salesforce.api.SalesforceOpportunityQuery;
import secondbrain.infrastructure.salesforce.api.SalesforceTaskRecord;

import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * A mock implementation of the SalesforceClient interface for testing purposes.
 */
@ApplicationScoped
public class SalesforceClientMock implements SalesforceClient {

    @Override
    public boolean anyItemsInDuration(final String token, final String accountId, final ChronoUnit duration, final ChronoUnit cached) {
        return false;
    }

    @Override
    public SalesforceOauthTokenResponse getToken(final String clientId, final String clientSecret) {
        return new SalesforceOauthTokenResponse("mock_token", "https://mock.salesforce.com", "mock_id", "Bearer", "0", "mock_signature");
    }

    @Override
    public SalesforceTaskRecord[] getTasks(final String token, final String accountId, final String type, final String startDate, final String endDate) {
        return new SalesforceTaskRecord[0];
    }

    @Override
    public SalesforceEmailRecord[] getEmails(final String token, final String accountId, final String startDate, final String endDate) {
        return new SalesforceEmailRecord[0];
    }

    @Override
    public SalesforceOpportunityQuery getOpportunityByAccountId(final String token, final String accountId) {
        return new SalesforceOpportunityQuery(List.of());
    }

    @Override
    public List<String> getAccountAndOpportunityIds(final String token, final String accountId) {
        return List.of(accountId);
    }
}
