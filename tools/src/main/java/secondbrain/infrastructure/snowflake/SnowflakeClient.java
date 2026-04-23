package secondbrain.infrastructure.snowflake;

import java.util.List;

public interface SnowflakeClient {
    List<SnowflakeLicenseDetails> getLicenseDetails(String id);
}

