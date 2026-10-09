package secondbrain.infrastructure.snowflake;

import java.util.List;
import java.util.Optional;

public interface SnowflakeClient {
    List<SnowflakeLicenseDetails> getLicenseDetails(String id);

    /**
     * Returns the most recent adoption index for the given account ID.
     */
    Optional<SnowflakeAdoptionIndexDetails> getAdoptionIndex(String id);
}
