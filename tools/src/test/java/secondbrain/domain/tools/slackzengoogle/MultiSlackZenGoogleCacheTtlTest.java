package secondbrain.domain.tools.slackzengoogle;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.hooks.NamedHooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.sanitize.GetFirstMarkdownBlock;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.domain.test.RecordingLocalStorage;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tools.keyword.Keywords;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(MultiSlackZenGoogleConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(Keywords.class)
@AddBeanClasses(NamedHooksContainer.class)
@AddBeanClasses(GetFirstMarkdownBlock.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(Loggers.class)
class MultiSlackZenGoogleCacheTtlTest {
    private static final int ONE_DAY = 60 * 60 * 24;

    @Inject
    private MultiSlackZenGoogleConfig config;

    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return new RecordingLlmClient();
    }

    @Produces
    @Preferred
    LocalStorage produceLocalStorage() {
        return new RecordingLocalStorage();
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("multi-slack-zen-google-cache-ttl", 7);
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
    }

    private int cacheTtl(final String ttl) {
        return config.new LocalArguments(List.of(new ToolArgs(MultiSlackZenGoogle.MULTI_SLACK_ZEN_TTL_SECONDS_ARG, ttl, true)), List.of("prompt"), Map.of()).getCacheTtl();
    }

    @Test
    void cacheTtlDefaultsToOneDay() {
        assertEquals(ONE_DAY, config.new LocalArguments(List.of(), List.of("prompt"), Map.of()).getCacheTtl());
    }

    @ParameterizedTest
    @CsvSource({"60, 60", "0, 0", "-5, 0", "abc, " + ONE_DAY, "1.5, " + ONE_DAY})
    void cacheTtlIsReadFromTheArgument(final String ttl, final int expected) {
        assertEquals(expected, cacheTtl(ttl));
    }

    @Test
    void invalidConfiguredCacheTtlFallsBackToOneDay() {
        TestConfigUtil.registerConfig(Map.of("sb.multislackzengoogle.ttlSeconds", "abc"));

        assertEquals(ONE_DAY, cacheTtl("60"));
    }
}
