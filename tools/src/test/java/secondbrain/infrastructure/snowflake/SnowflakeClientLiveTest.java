package secondbrain.infrastructure.snowflake;

import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class SnowflakeClientLiveTest {
    @Test
    void testConnection() throws SQLException {
        final SnowflakeClientLive snowflakeClientLive = new SnowflakeClientLive();
        final String jwt = new String(Base64.getDecoder().decode(System.getenv("SB_SNOWFLAKE_JWT_BASE64")));
        snowflakeClientLive.openConnection(
                System.getenv("SB_SNOWFLAKE_USER"),
                jwt,
                "jdbc:snowflake://" + System.getenv("SB_SNOWFLAKE_URL") + ".snowflakecomputing.com");
        final ResultSet rs = snowflakeClientLive.getLicenseDetails("001Qq00000YrmcPIAR");
        assertNotNull(rs);

        final int columnCount = rs.getMetaData().getColumnCount();
        while (rs.next()) {
            for (int i = 1; i <= columnCount; i++) {
                System.out.println(rs.getMetaData().getColumnName(i) + ": " + rs.getString(i));
            }
            System.out.println("---");
        }

        snowflakeClientLive.closeConnection();
    }
}


