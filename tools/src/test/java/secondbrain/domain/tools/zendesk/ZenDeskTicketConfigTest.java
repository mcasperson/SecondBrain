package secondbrain.domain.tools.zendesk;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jboss.weld.junit5.auto.ExcludeBeanClasses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.tools.keyword.Keywords;
import secondbrain.domain.validate.ValidateStringBlank;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(ZenDeskTicketConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
// Keywords is replaced by a mock from a producer method
@ExcludeBeanClasses(Keywords.class)
class ZenDeskTicketConfigTest {
    private final Keywords keywordsTool = mock(Keywords.class);

    @Inject
    private ZenDeskTicketConfig config;

    @Produces
    Keywords produceKeywords() {
        return keywordsTool;
    }

    @Produces
    @Identifier("AES")
    Encryptor produceEncryptor() {
        return TestArgs.encryptor();
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("zendesk-ticket-config", 17);
    }

    @BeforeEach
    void registerConfig() {
        TestConfigUtil.registerConfig(Map.of());
    }

    private ZenDeskTicketConfig.LocalArguments local(final List<ToolArgs> arguments, final Map<String, String> context) {
        return config.new LocalArguments(arguments, List.of("prompt"), context);
    }

    private static List<ToolArgs> toolArgs(final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return arguments;
    }

    private ZenDeskTicketConfig.LocalArguments args(final String... nameValues) {
        return local(toolArgs(nameValues), Map.of());
    }

    private ZenDeskTicketConfig.LocalArguments context(final String... nameValues) {
        final Map<String, String> context = new HashMap<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            context.put(nameValues[i], nameValues[i + 1]);
        }
        return local(List.of(), context);
    }

    @Test
    void ticketDetailsAreReadFromArgumentsAndDefaultToBlank() {
        final ZenDeskTicketConfig.LocalArguments local = args(
                ZenDeskIndividualTicket.ZENDESK_TICKET_ID_ARG, "123",
                ZenDeskIndividualTicket.ZENDESK_TICKET_SUBJECT_ARG, "Broken login",
                ZenDeskIndividualTicket.ZENDESK_TICKET_SUBMITTER_ARG, "u1",
                ZenDeskIndividualTicket.ZENDESK_TICKET_ORGANIZATION_ARG, "o1",
                ZenDeskIndividualTicket.ZENDESK_TICKET_CREATED_AT_ARG, "2024-01-01",
                ZenDeskIndividualTicket.ZENDESK_TICKET_ASSIGNEE_ARG, "a1");

        assertEquals("123", local.getTicketId());
        assertEquals("Broken login", local.getTicketSubject());
        assertEquals("u1", local.getTicketSubmitter());
        assertEquals("o1", local.getTicketOrganization());
        assertEquals("2024-01-01", local.getCreatedAt());
        assertEquals("a1", local.getTicketAssignee());

        final ZenDeskTicketConfig.LocalArguments empty = args();
        assertEquals("", empty.getTicketId());
        assertEquals("", empty.getTicketSubject());
        assertEquals("", empty.getTicketSubmitter());
        assertEquals("", empty.getTicketOrganization());
        assertEquals("", empty.getCreatedAt());
        assertEquals("", empty.getTicketAssignee());
    }

    @Test
    void ticketIdCanComeFromContext() {
        assertEquals("from-context", context(ZenDeskIndividualTicket.ZENDESK_TICKET_ID_ARG, "from-context").getTicketId());
    }

    @Test
    void ticketDetailsFromConfigTakePrecedence() {
        // Config must be registered before the config bean is first used, as CDI creates it lazily
        TestConfigUtil.registerConfig(Map.of("sb.zendesk.ticketid", "from-config", "sb.zendesk.tickercreatedat", "2023-12-31"));

        assertEquals("from-config", args(ZenDeskIndividualTicket.ZENDESK_TICKET_ID_ARG, "argument").getTicketId());
        assertEquals("2023-12-31", args(ZenDeskIndividualTicket.ZENDESK_TICKET_CREATED_AT_ARG, "argument").getCreatedAt());
    }

    @Test
    void tokenIsDecryptedFromContextOrTakenAsPlainText() {
        assertEquals("secret", context(ZenDeskIndividualTicket.ZENDESK_TOKEN_ARG, "enc(secret)").getSecretToken());
        assertEquals("plain", context(ZenDeskIndividualTicket.ZENDESK_TOKEN_ARG, "plain").getSecretToken());
    }

    @Test
    void tokenFallsBackToArgumentAndFailsWhenMissing() {
        assertEquals("arg-token", args(ZenDeskIndividualTicket.ZENDESK_TOKEN_ARG, "arg-token").getSecretToken());
        assertThrows(InternalFailure.class, () -> args().getSecretToken());
    }

    @Test
    void tokenFromConfigTakesPrecedenceOverArgument() {
        TestConfigUtil.registerConfig(Map.of("sb.zendesk.accesstoken", "config-token"));

        assertEquals("config-token", args(ZenDeskIndividualTicket.ZENDESK_TOKEN_ARG, "arg-token").getSecretToken());
    }

    @Test
    void urlComesFromContextThenArgument() {
        assertEquals("https://enc.zendesk.com", context("zendesk_url", "enc(https://enc.zendesk.com)").getUrl());
        assertEquals("https://plain.zendesk.com", context("zendesk_url", "https://plain.zendesk.com").getUrl());
        assertEquals("https://arg.zendesk.com", args(ZenDeskIndividualTicket.ZENDESK_URL_ARG, "https://arg.zendesk.com").getUrl());
        assertThrows(InternalFailure.class, () -> args().getUrl());
    }

    @Test
    void urlFromConfigTakesPrecedenceOverArgument() {
        TestConfigUtil.registerConfig(Map.of("sb.zendesk.url", "https://config.zendesk.com"));

        assertEquals("https://config.zendesk.com", args(ZenDeskIndividualTicket.ZENDESK_URL_ARG, "https://arg.zendesk.com").getUrl());
    }

    @Test
    void userComesFromContextThenArgument() {
        assertEquals("enc@example.org", context("zendesk_user", "enc(enc@example.org)").getUser());
        assertEquals("plain@example.org", context("zendesk_user", "plain@example.org").getUser());
        assertEquals("arg@example.org", args(ZenDeskIndividualTicket.ZENDESK_EMAIL_ARG, "arg@example.org").getUser());
        assertThrows(InternalFailure.class, () -> args().getUser());
    }

    @Test
    void userFromConfigTakesPrecedenceOverArgument() {
        TestConfigUtil.registerConfig(Map.of("sb.zendesk.user", "config@example.org"));

        assertEquals("config@example.org", args(ZenDeskIndividualTicket.ZENDESK_EMAIL_ARG, "arg@example.org").getUser());
    }

    @Test
    void blankContextValuesFallBackToArguments() {
        final ZenDeskTicketConfig.LocalArguments local = local(
                toolArgs(ZenDeskIndividualTicket.ZENDESK_EMAIL_ARG, "arg@example.org", ZenDeskIndividualTicket.ZENDESK_TOKEN_ARG, "arg-token"),
                Map.of("zendesk_user", " ", ZenDeskIndividualTicket.ZENDESK_TOKEN_ARG, " "));

        assertEquals("arg@example.org", local.getUser());
        assertEquals("arg-token", local.getSecretToken());
    }

    @Test
    void authHeaderIsBasicAuthWithTheApiTokenSuffix() {
        final String header = context("zendesk_user", "me@example.org", ZenDeskIndividualTicket.ZENDESK_TOKEN_ARG, "tok").getSecretAuthHeader();

        assertEquals("Basic " + Base64.getEncoder().encodeToString("me@example.org/token:tok".getBytes(StandardCharsets.UTF_8)), header);
    }

    @Test
    void authHeaderFailsWithoutCredentials() {
        assertThrows(InternalFailure.class, () -> args().getSecretAuthHeader());
    }

    @Test
    void searchTtlDefaultsToNinetyDaysAndIsNonNegative() {
        assertEquals(60 * 60 * 24 * 90, args().getSearchTTL());
        assertEquals(0, args(ZenDeskIndividualTicket.ZENDESK_HISTORY_TTL_ARG, "-1").getSearchTTL());
        assertEquals(30, args(ZenDeskIndividualTicket.ZENDESK_HISTORY_TTL_ARG, "30").getSearchTTL());
        assertThrows(NumberFormatException.class, () -> args(ZenDeskIndividualTicket.ZENDESK_HISTORY_TTL_ARG, "long").getSearchTTL());
    }

    @ParameterizedTest
    @CsvSource({"'',100", "5,5", "1,1", "0,1", "-4,1", "many,100"})
    void numCommentsDefaultsToOneHundredAndIsAtLeastOne(final String value, final int expected) {
        assertEquals(expected, args(ZenDeskOrganization.NUM_COMMENTS_ARG, value).getNumComments());
    }

    @Test
    void ratingSettingsHaveDefaultsAndBounds() {
        assertEquals("", args().getContextFilterQuestion());
        assertEquals("Is it urgent?", args(ZenDeskIndividualTicket.ZENDESK_RATING_QUESTION_ARG, "Is it urgent?").getContextFilterQuestion());
        assertEquals(10, args().getDefaultRating());
        assertEquals(3, args(ZenDeskIndividualTicket.ZENDESK_DEFAULT_RATING_ARG, "3").getDefaultRating());
        assertEquals(0, args(ZenDeskIndividualTicket.ZENDESK_DEFAULT_RATING_ARG, "-3").getDefaultRating());
        assertEquals(10, args(ZenDeskIndividualTicket.ZENDESK_DEFAULT_RATING_ARG, "abc").getDefaultRating());
    }

    @Test
    void keywordsComeFromArgumentsAndCanBeAutoGenerated() {
        assertEquals(List.of("alpha", "beta"), args(CommonArguments.KEYWORDS_ARG, " alpha , beta ").getKeywords());
        assertFalse(args().getAutoGenerateKeywords());

        // The prompts and the rating question are used to generate the keywords
        when(keywordsTool.getKeywords(Map.of(), List.of("prompt", "urgent?"), List.of())).thenReturn(List.of("delta"));
        final ZenDeskTicketConfig.LocalArguments local = args(
                CommonArguments.KEYWORDS_ARG, "alpha",
                CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, "true",
                ZenDeskIndividualTicket.ZENDESK_RATING_QUESTION_ARG, "urgent?");

        assertTrue(local.getAutoGenerateKeywords());
        assertEquals(List.of("alpha", "delta"), local.getKeywords());
    }

    @Test
    void keywordWindowFallsBackToDefault() {
        assertEquals(Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH, args().getKeywordWindow());
        assertEquals(Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH, args(CommonArguments.KEYWORD_WINDOW_ARG, "x").getKeywordWindow());
        assertEquals(250, args(CommonArguments.KEYWORD_WINDOW_ARG, "250").getKeywordWindow());
    }

    @Test
    void entityIsOnlyReadFromContext() {
        assertEquals("Acme", context(ZenDeskIndividualTicket.ZENDESK_ENTITY_NAME_CONTEXT_ARG, "Acme").getEntity());
        assertEquals("", args(ZenDeskIndividualTicket.ZENDESK_ENTITY_NAME_CONTEXT_ARG, "Acme").getEntity());
    }

    @Test
    void toStringAndHashCodeDelegateToTheGenerator() {
        assertEquals("zendesk-ticket-config", args().toString());
        assertEquals(17, args().hashCode());
    }
}
