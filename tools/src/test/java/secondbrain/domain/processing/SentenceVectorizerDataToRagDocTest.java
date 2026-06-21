package secondbrain.domain.processing;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.apache.tika.utils.StringUtils;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.config.LocalConfigKeywordsEntity;
import secondbrain.domain.context.JdlSentenceVectorizer;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.SimpleSentenceSplitter;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;
import secondbrain.domain.encryption.AesEncryptor;
import secondbrain.domain.encryption.JasyptEncryptor;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.limit.DocumentTrimmerExactKeywords;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.persist.LocalStorageReadWrite;
import secondbrain.domain.persist.MockLocalStorage;
import secondbrain.domain.persist.MockLocalStorageReadWrite;
import secondbrain.domain.sanitize.FinancialLocationContactRedaction;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.testconstants.TestConstants;
import secondbrain.domain.zip.ApacheCommonsZStdZipper;
import secondbrain.domain.zip.ApacheCompressZipper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SuppressWarnings("NullAway")
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SentenceVectorizerDataToRagDoc.class)
@AddBeanClasses(SimpleSentenceSplitter.class)
@AddBeanClasses(MockLocalStorage.class)
@AddBeanClasses(MockLocalStorageReadWrite.class)
@AddBeanClasses(JdlSentenceVectorizer.class)
@AddBeanClasses(DocumentTrimmerExactKeywords.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(LoggingExceptionHandler.class)
@AddBeanClasses(JasyptEncryptor.class)
@AddBeanClasses(AesEncryptor.class)
@AddBeanClasses(ApacheCompressZipper.class)
@AddBeanClasses(ApacheCommonsZStdZipper.class)
@AddBeanClasses(FinancialLocationContactRedaction.class)
class SentenceVectorizerDataToRagDocTest {

    @Inject
    private SentenceVectorizerDataToRagDoc sentenceVectorizerDataToRagDoc;

    @Produces
    @Preferred
    @ApplicationScoped
    public LocalStorage produceLocalStorage() {
        return new MockLocalStorage();
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public LocalStorageReadWrite produceLocalStorageReadWrite() {
        return new MockLocalStorageReadWrite();
    }

    @BeforeAll
    static void registerConfig() {
        final String autodiscovery = System.getenv("SB_COSMOS_AUTODISCOVERY");
        final String gatewayMode = System.getenv("SB_COSMOS_GATEWAYMODE");

        TestConfigUtil.registerConfig(Map.of(
                "sb.infrastructure.mock", "true",
                "sb.encryption.password", "1234567890",
                "sb.encryption.salt", "1234567890",
                "sb.cosmos.endpoint", TestConstants.COSMOS_EMULATOR_ENDPOINT,
                "sb.cosmos.key", TestConstants.COSMOS_EMULATOR_KEY,
                "sb.cosmos.lockdatabase", "secondbrainlock",
                "sb.cosmos.lockscontainer", "locks",
                "sb.cosmos.autodiscovery", StringUtils.isBlank(autodiscovery) ? "true" : autodiscovery,
                "sb.cosmos.gatewayMode", StringUtils.isBlank(gatewayMode) ? "false" : gatewayMode
        ));
    }

    @BeforeEach
    void updateConfig() {
        registerConfig();
    }

    @Test
    void testGetDocumentContext_Success() {
        final TestTask task = new TestTask(
                "test-id",
                "My sample keyword document",
                "https://example.com",
                "Example");
        final LocalConfigKeywordsEntity parsedArgs = new LocalConfigKeywordsEntity() {
            @Override
            public List<String> getKeywords() {
                return List.of("keyword");
            }

            @Override
            public int getKeywordWindow() {
                return 50;
            }

            @Override
            public String getEntity() {
                return "test-entity";
            }
        };

        final RagDocumentContext<TestTask> result = sentenceVectorizerDataToRagDoc.getDocumentContext(
                task, "toolName", "contextLabel", parsedArgs);

        assertNotNull(result);
        assertEquals("toolName", result.tool());
        assertEquals("contextLabel", result.contextLabel());
        assertEquals("My sample keyword document", result.document());
        assertEquals("test-id", result.id());
        assertEquals("[Example](https://example.com)", result.link());
        assertEquals(1, result.keywordMatches().size());
    }

    private record TestTask(String id, String text, String url, String linkText) implements TextData, IdData, UrlData {
        @Override
        public String generateId() {
            return id;
        }

        @Override
        public String generateText() {
            return text;
        }

        @Override
        public String generateLinkText() {
            return linkText;
        }

        @Override
        public String generateUrl() {
            return url;
        }
    }
}
