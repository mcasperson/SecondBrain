package secondbrain.domain.vector;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import secondbrain.domain.context.JdlSentenceVectorizer;
import secondbrain.domain.context.RagStringContext;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.persist.H2LocalStorage;
import secondbrain.domain.persist.LocalStorageProducer;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(JdlSentenceVectorizer.class)
@AddBeanClasses(H2LocalStorage.class)
@AddBeanClasses(LocalStorageProducer.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(LoggingExceptionHandler.class)
public class JdlSentenceVectorizerTest {

    @Inject
    private JdlSentenceVectorizer jdlSentenceVectorizer;

    @Test
    public void testVectorize() {
        final String text = "This is a test sentence.";
        final RagStringContext vector = jdlSentenceVectorizer.vectorize(text);
        assertNotNull(vector.vector());
    }
}
