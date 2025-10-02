package secondbrain.infrastructure.salesforce;

import secondbrain.infrastructure.salesforce.api.SalesforceOauthTokenResponse;
import secondbrain.infrastructure.salesforce.api.SalesforceTaskRecord;

public interface SalesforceClient {
    SalesforceOauthTokenResponse getToken(String clientId, String clientSecret);

    SalesforceTaskRecord[] getTasks(String accountId, String type);
}
