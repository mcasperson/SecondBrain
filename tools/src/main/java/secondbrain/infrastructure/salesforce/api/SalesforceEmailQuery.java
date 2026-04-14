package secondbrain.infrastructure.salesforce.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SalesforceEmailQuery(Integer totalSize, Boolean done, List<SalesforceEmailRecord> records) {
    public SalesforceEmailRecord[] getRecordsArray() {
        return records.toArray(new SalesforceEmailRecord[0]);
    }
}

