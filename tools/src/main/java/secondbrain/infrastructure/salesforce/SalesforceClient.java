package secondbrain.infrastructure.salesforce;

import secondbrain.infrastructure.salesforce.api.SalesforceOauthTokenResponse;
import secondbrain.infrastructure.salesforce.api.SalesforceOpportunityQuery;
import secondbrain.infrastructure.salesforce.api.SalesforceTaskRecord;

public interface SalesforceClient {
    SalesforceOauthTokenResponse getToken(String clientId, String clientSecret);

    SalesforceTaskRecord[] getTasks(String token, String accountId, String type, String startDate, String endDate);

    SalesforceOpportunityQuery getOpportunityByAccountId(String token, String accountId);


}
