package secondbrain.infrastructure.salesforce.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SalesforceTaskQuery(Integer totalSize, Boolean done, SalesforceTaskRecord[] records) {
}
