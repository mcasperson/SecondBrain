package secondbrain.infrastructure.salesforce.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SalesforceOpportunityQuery(List<Map<String, Object>> records) {
	public List<Map<String, Object>> getRecords() {
		return Objects.requireNonNullElse(records, List.of());
	}
}
