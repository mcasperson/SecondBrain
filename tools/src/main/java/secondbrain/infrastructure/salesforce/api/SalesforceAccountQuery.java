package secondbrain.infrastructure.salesforce.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SalesforceAccountQuery(@Nullable Integer totalSize, @Nullable Boolean done,
                                     @Nullable List<SalesforceAccountRecord> records) {
    public List<SalesforceAccountRecord> getRecords() {
        return Objects.requireNonNullElse(records, List.of());
    }
}
