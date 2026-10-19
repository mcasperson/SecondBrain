package secondbrain.infrastructure.snowflake;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.test.TestConfigUtil;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Connects to a real Snowflake account, so the production SnowflakeConnectionProducer is used.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SnowflakeClientLive.class)
@AddBeanClasses(SnowflakeConnectionProducer.class)
class SnowflakeClientLiveTest {
    @Inject
    @Preferred
    private SnowflakeClientLive snowflakeClientLive;

    @BeforeEach
    void registerConfig() {
        TestConfigUtil.registerConfig(Map.of(
                "sb.snowflake.user", Objects.requireNonNullElse(System.getenv("SB_SNOWFLAKE_USER"), ""),
                "sb.snowflake.jwt.base64", Objects.requireNonNullElse(System.getenv("SB_SNOWFLAKE_JWT_BASE64"), ""),
                "sb.snowflake.url", Objects.requireNonNullElse(System.getenv("SB_SNOWFLAKE_URL"), "")));
    }

    @Test
    @Disabled
    void testConnection() {
        final List<SnowflakeLicenseDetails> results = snowflakeClientLive.getLicenseDetails("0017V000024lHNiQAM");
        assertNotNull(results);

        results.forEach(System.out::println);
    }
}
