package secondbrain.domain.processing;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import secondbrain.domain.config.LocalConfigKeywordsEntity;
import secondbrain.domain.context.JdlSentenceVectorizer;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.SimpleSentenceSplitter;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.limit.DocumentTrimmerExactKeywords;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.persist.H2LocalStorage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SentenceVectorizerDataToRagDoc.class)
@AddBeanClasses(SimpleSentenceSplitter.class)
@AddBeanClasses(JdlSentenceVectorizer.class)
@AddBeanClasses(DocumentTrimmerExactKeywords.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(H2LocalStorage.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(LoggingExceptionHandler.class)
class SentenceVectorizerDataToRagDocTest {

    @Inject
    private SentenceVectorizerDataToRagDoc sentenceVectorizerDataToRagDoc;

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
        public String getId() {
            return id;
        }

        @Override
        public String getText() {
            return text;
        }

        @Override
        public String getLinkText() {
            return linkText;
        }

        @Override
        public String getUrl() {
            return url;
        }
    }
}
