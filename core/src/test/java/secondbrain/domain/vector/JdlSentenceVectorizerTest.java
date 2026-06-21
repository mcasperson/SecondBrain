package secondbrain.domain.vector;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.apache.tika.utils.StringUtils;
import org.eclipse.microprofile.config.Config;
import secondbrain.domain.testconstants.TestConstants;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import secondbrain.domain.config.MockConfig;
import secondbrain.domain.context.JdlSentenceVectorizer;
import secondbrain.domain.context.RagStringContext;
import secondbrain.domain.encryption.AesEncryptor;
import secondbrain.domain.encryption.JasyptEncryptor;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.persist.CosmosLocalStorage;
import secondbrain.domain.persist.FileLocalStorageReadWrite;
import secondbrain.domain.persist.H2LocalStorage;
import secondbrain.domain.persist.LocalStorageProducer;
import secondbrain.domain.persist.LocalStorageReadWriteProducer;
import secondbrain.domain.persist.MockLocalStorageReadWrite;
import secondbrain.domain.sanitize.FinancialLocationContactRedaction;
import secondbrain.domain.zip.ApacheCommonsZStdZipper;
import secondbrain.domain.zip.ApacheCompressZipper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(JdlSentenceVectorizer.class)
@AddBeanClasses(H2LocalStorage.class)
@AddBeanClasses(LocalStorageProducer.class)
@AddBeanClasses(CosmosLocalStorage.class)
@AddBeanClasses(FileLocalStorageReadWrite.class)
@AddBeanClasses(MockLocalStorageReadWrite.class)
@AddBeanClasses(LocalStorageReadWriteProducer.class)
@AddBeanClasses(MockConfig.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(LoggingExceptionHandler.class)
@AddBeanClasses(JasyptEncryptor.class)
@AddBeanClasses(AesEncryptor.class)
@AddBeanClasses(AesEncryptor.class)
@AddBeanClasses(ApacheCompressZipper.class)
@AddBeanClasses(ApacheCommonsZStdZipper.class)
@AddBeanClasses(FinancialLocationContactRedaction.class)
public class JdlSentenceVectorizerTest {

    @Inject
    private JdlSentenceVectorizer jdlSentenceVectorizer;

    @BeforeAll
    static void registerConfig() {
        final String autodiscovery = System.getenv("SB_COSMOS_AUTODISCOVERY");
        final String gatewayMode = System.getenv("SB_COSMOS_GATEWAYMODE");

        final var configSource = new PropertiesConfigSource(
                Map.of(
                        "sb.infrastructure.mock", "true",
                        "sb.encryption.password", "1234567890",
                        "sb.encryption.salt", "1234567890",
                        "sb.cosmos.endpoint", TestConstants.COSMOS_EMULATOR_ENDPOINT,
                        "sb.cosmos.key", TestConstants.COSMOS_EMULATOR_KEY,
                        "sb.cosmos.autodiscovery", StringUtils.isBlank(autodiscovery) ? "true" : autodiscovery,
                        "sb.cosmos.gatewayMode", StringUtils.isBlank(gatewayMode) ? "false" : gatewayMode
                ),
                "TestConfig",
                Integer.MAX_VALUE
        );
        final Config newConfig = new SmallRyeConfigBuilder()
                .withSources(configSource)
                .build();

        final var configProviderResolver = ConfigProviderResolver.instance();
        final var oldConfig = configProviderResolver.getConfig();

        configProviderResolver.releaseConfig(oldConfig);
        configProviderResolver.registerConfig(
                newConfig,
                Thread.currentThread().getContextClassLoader()
        );
    }

    @Test
    public void testVectorize() {
        final String text = "This is a test sentence.";
        final RagStringContext vector = jdlSentenceVectorizer.vectorize(text);
        assertNotNull(vector.vector());
    }
}
