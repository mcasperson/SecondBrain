package secondbrain.infrastructure.salesforce.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SalesforceTaskQuery(Integer totalSize, Boolean done, List<SalesforceTaskRecord> records) {
    public SalesforceTaskRecord[] getRecordsArray() {
        return records.toArray(new SalesforceTaskRecord[0]);
    }
}
