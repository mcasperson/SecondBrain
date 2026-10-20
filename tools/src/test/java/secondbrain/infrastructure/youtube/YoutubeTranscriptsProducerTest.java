package secondbrain.infrastructure.youtube;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link YoutubeTranscriptsProducer}. Creating the default transcript API only builds an HTTP client, so
 * no network calls are made. The produced instance is never asked to list transcripts, as that would hit YouTube.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(YoutubeTranscriptsProducer.class)
class YoutubeTranscriptsProducerTest {
    @Inject
    private Instance<YoutubeTranscripts> transcripts;

    @Test
    void producesTheLiveAdapter() {
        assertInstanceOf(YoutubeTranscriptsLive.class, transcripts.get());
    }

    @Test
    void producesANewInstanceForEachInjection() {
        // The producer method has the default dependent scope
        assertNotSame(transcripts.get(), transcripts.get());
    }
}
