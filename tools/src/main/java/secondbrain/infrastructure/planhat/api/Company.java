package secondbrain.infrastructure.planhat.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.annotations.PropertyLabel;

import java.util.Map;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Company(@JsonProperty("_id") String id,
                      String name,
                      @PropertyLabel(description = "Owner", type="Person") @Nullable String owner,
                      @PropertyLabel(description = "Renewal Date") @Nullable String renewalDate,
                      @PropertyLabel(description = "Health Score") @JsonProperty("h") @Nullable Integer health,
                      Map<String, Integer> usage,
                      Map<String, Object> custom) {

    @Nullable public Object getCustomKey(final String key) {
        return custom != null ? custom.get(key) : null;
    }

    public String getCustomStringKey(final String key) {
        final Object value = getCustomKey(key);

        if (value == null) {
            return "";
        }

        return value.toString();
    }

    public Company updateCustom(final Map<String, Object> custom) {
        return new Company(this.id, this.name, this.owner, this.renewalDate, this.health, this.usage, custom);
    }
}
