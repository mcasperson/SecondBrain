package secondbrain.domain.tools.planhat;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.validate.ValidateStringBlank;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(PlanHatUsageConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
class PlanHatUsageConfigTest {
    @Inject
    private PlanHatUsageConfig config;

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("planhat-usage-config", 3);
    }

    @BeforeEach
    void registerConfig() {
        TestConfigUtil.registerConfig(Map.of());
    }

    private PlanHatUsageConfig.LocalArguments local(final Map<String, String> context, final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return config.new LocalArguments(arguments, List.of("prompt"), context);
    }

    private PlanHatUsageConfig.LocalArguments args(final String... nameValues) {
        return local(Map.of(), nameValues);
    }

    @Test
    void companyComesFromArgumentsWhenNotConfigured() {
        assertEquals("co-arg", args(PlanHatUsage.COMPANY_ID_ARGS, "co-arg").getCompany());
    }

    @Test
    void configuredCompanyTakesPrecedenceOverArguments() {
        TestConfigUtil.registerConfig(Map.of("sb.planhat.company", "co-config"));

        assertEquals("co-config", args(PlanHatUsage.COMPANY_ID_ARGS, "co-arg").getCompany());
    }

    @Test
    void urlDefaultsToTheUsApiAndIgnoresContext() {
        // sb.planhat.url has a default value, so the planhat_url context fallback is never reached
        assertEquals("https://api-us4.planhat.com", local(Map.of("planhat_url", "https://ctx")).getUrl());
    }

    @Test
    void configuredUrlIsUsed() {
        TestConfigUtil.registerConfig(Map.of("sb.planhat.url", "https://config"));

        assertEquals("https://config", local(Map.of("planhat_url", "https://ctx")).getUrl());
    }

    @Test
    void secondaryUrlFallsBackToContextThenEmpty() {
        assertEquals("", args().getUrl2());
        assertEquals("https://ctx2", local(Map.of("planhat_url2", "https://ctx2")).getUrl2());
    }

    @Test
    void configuredSecondaryUrlTakesPrecedenceOverContext() {
        TestConfigUtil.registerConfig(Map.of("sb.planhat.url2", "https://config2"));

        assertEquals("https://config2", local(Map.of("planhat_url2", "https://ctx2")).getUrl2());
    }

    @Test
    void tokensFallBackToContextThenEmpty() {
        assertEquals("", args().getSecretToken());
        assertEquals("", args().getSecretToken2());
        assertEquals("ctx-token", local(Map.of("planhat_usage_token", "ctx-token")).getSecretToken());
        assertEquals("ctx-token2", local(Map.of("planhat_token2", "ctx-token2")).getSecretToken2());
    }

    @Test
    void configuredTokensTakePrecedenceOverContext() {
        TestConfigUtil.registerConfig(Map.of("sb.planhat.accesstoken", "config-token", "sb.planhat.accesstoken2", "config-token2"));

        assertEquals("config-token", local(Map.of("planhat_usage_token", "ctx-token")).getSecretToken());
        assertEquals("config-token2", local(Map.of("planhat_token2", "ctx-token2")).getSecretToken2());
    }

    @Test
    void searchTtlDefaultsToOneWeekAndIsNonNegative() {
        assertEquals(60 * 60 * 24 * 7, args().getSearchTTL());
        assertEquals(0, args(PlanHatUsage.SEARCH_TTL_ARG, "-1").getSearchTTL());
        assertEquals(30, args(PlanHatUsage.SEARCH_TTL_ARG, "30").getSearchTTL());
    }

    @Test
    void customFieldsAreReturnedInOrderWithBlanksForMissingValues() {
        final PlanHatUsageConfig.LocalArguments local = args(
                PlanHatUsage.PLANHAT_CUSTOM_1_ARG, "c1",
                PlanHatUsage.PLANHAT_CUSTOM_3_ARG, "c3",
                PlanHatUsage.PLANHAT_CUSTOM_5_ARG, "c5");

        assertEquals(List.of("c1", "", "c3", "", "c5"), local.getCustoms());
    }

    @Test
    void allCustomFieldsCanBeSet() {
        final PlanHatUsageConfig.LocalArguments local = args(
                PlanHatUsage.PLANHAT_CUSTOM_1_ARG, "c1",
                PlanHatUsage.PLANHAT_CUSTOM_2_ARG, "c2",
                PlanHatUsage.PLANHAT_CUSTOM_3_ARG, "c3",
                PlanHatUsage.PLANHAT_CUSTOM_4_ARG, "c4",
                PlanHatUsage.PLANHAT_CUSTOM_5_ARG, "c5");

        assertEquals(List.of("c1", "c2", "c3", "c4", "c5"), local.getCustoms());
    }

    @Test
    void usagePairsMatchNamesToIds() {
        final PlanHatUsageConfig.LocalArguments local = args(
                PlanHatUsage.PLANHAT_USAGE_NAME_1_ARG, "Logins", PlanHatUsage.PLANHAT_USAGE_ID_1_ARG, "u1",
                PlanHatUsage.PLANHAT_USAGE_NAME_2_ARG, "Deploys", PlanHatUsage.PLANHAT_USAGE_ID_2_ARG, "u2",
                PlanHatUsage.PLANHAT_USAGE_NAME_3_ARG, "Builds", PlanHatUsage.PLANHAT_USAGE_ID_3_ARG, "u3",
                PlanHatUsage.PLANHAT_USAGE_NAME_4_ARG, "Users", PlanHatUsage.PLANHAT_USAGE_ID_4_ARG, "u4",
                PlanHatUsage.PLANHAT_USAGE_NAME_5_ARG, "Projects", PlanHatUsage.PLANHAT_USAGE_ID_5_ARG, "u5");

        assertEquals(List.of(
                Pair.of("Logins", "u1"),
                Pair.of("Deploys", "u2"),
                Pair.of("Builds", "u3"),
                Pair.of("Users", "u4"),
                Pair.of("Projects", "u5")), local.getUsagePairs().toList());
    }

    @Test
    void usageIdsAndNamesComeFromConfigBeforeArguments() {
        TestConfigUtil.registerConfig(Map.of("sb.planhat.usageid1", "config-id", "sb.planhat.usagename5", "config-name"));

        final PlanHatUsageConfig.LocalArguments local = args(
                PlanHatUsage.PLANHAT_USAGE_ID_1_ARG, "arg-id",
                PlanHatUsage.PLANHAT_USAGE_NAME_5_ARG, "arg-name");

        assertEquals("config-id", local.getUsageId1());
        assertEquals("config-name", local.getUsageName5());
        assertEquals(Pair.of("", ""), local.getUsagePairs().toList().get(1));
    }

    @Test
    void customPersonsAreReturnedInOrder() {
        final PlanHatUsageConfig.LocalArguments local = args(
                PlanHatUsage.PLANHAT_CUSTOM_PERSON_1_ARG, "Owner",
                PlanHatUsage.PLANHAT_CUSTOM_PERSON_2_ARG, "CSM",
                PlanHatUsage.PLANHAT_CUSTOM_PERSON_3_ARG, "AE",
                PlanHatUsage.PLANHAT_CUSTOM_PERSON_4_ARG, "SE",
                PlanHatUsage.PLANHAT_CUSTOM_PERSON_5_ARG, "Exec");

        assertEquals(List.of("Owner", "CSM", "AE", "SE", "Exec"), local.getCustomPersons().toList());
        assertEquals(List.of("", "", "", "", ""), args().getCustomPersons().toList());
    }

    @Test
    void toStringAndHashCodeDelegateToTheGenerator() {
        assertEquals("planhat-usage-config", args().toString());
        assertEquals(3, args().hashCode());
    }
}
