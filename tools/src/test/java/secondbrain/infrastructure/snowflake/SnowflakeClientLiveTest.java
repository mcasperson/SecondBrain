package secondbrain.infrastructure.snowflake;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SnowflakeClientLiveTest {
    @Test
    void testConnection() throws SQLException {
        final SnowflakeClientLive snowflakeClientLive = new SnowflakeClientLive();
        final String jwt = new String(Base64.getDecoder().decode(System.getenv("SB_SNOWFLAKE_JWT_BASE64")));
        final ResultSet rs = snowflakeClientLive.getLicenseDetails(
                System.getenv("SB_SNOWFLAKE_USER"),
                jwt,
                "jdbc:snowflake://" + System.getenv("SB_SNOWFLAKE_URL"));
        assertNotNull(rs);
    }
}

