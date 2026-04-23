package secondbrain.infrastructure.snowflake;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class SnowflakeClientLiveTest {
    @Test
    @Disabled
    void testConnection() {
        final SnowflakeClientLive snowflakeClientLive = new SnowflakeClientLive();
        final String jwt = new String(Base64.getDecoder().decode(System.getenv("SB_SNOWFLAKE_JWT_BASE64")));
        snowflakeClientLive.openConnection(
                System.getenv("SB_SNOWFLAKE_USER"),
                jwt,
                "jdbc:snowflake://" + System.getenv("SB_SNOWFLAKE_URL") + ".snowflakecomputing.com");

        final List<SnowflakeLicenseDetails> results = snowflakeClientLive.getLicenseDetails("001Qq00000YrmcPIAR");
        assertNotNull(results);

        results.forEach(System.out::println);

        snowflakeClientLive.closeConnection();
    }
}




