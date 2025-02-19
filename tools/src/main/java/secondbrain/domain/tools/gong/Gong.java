package secondbrain.domain.tools.gong;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.gong.GongCallExtensive;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

@ApplicationScoped
public class Gong implements Tool<GongCallExtensive> {
    public static final String DAYS_ARG = "days";
    public static final String COMPANY_ARG = "company";

    @Override
    public String getName() {
        return Gong.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns the transcripts of calls recorded in Gong";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of();
    }

    @Override
    public List<RagDocumentContext<GongCallExtensive>> getContext(
            final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        return List.of();
    }

    @Override
    public RagMultiDocumentContext<GongCallExtensive> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        return null;
    }

    @Override
    public String getContextLabel() {
        return "Gong Call";
    }
}

@ApplicationScoped
class GongConfig {
    @Inject
    @ConfigProperty(name = "sb.gong.accessKey")
    private Optional<String> configAccessKey;

    @Inject
    @ConfigProperty(name = "sb.gong.accessSecretKey")
    private Optional<String> configAccessSecretKey;

    @Inject
    @ConfigProperty(name = "sb.gong.days")
    private Optional<String> configDays;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    private ValidateString validateString;

    public Optional<String> getConfigDays() {
        return configDays;
    }

    public Optional<String> getConfigAccessKey() {
        return configAccessKey;
    }

    public Optional<String> getConfigAccessSecretKey() {
        return configAccessSecretKey;
    }

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public Encryptor getTextEncryptor() {
        return textEncryptor;
    }

    public ValidateString getValidateString() {
        return validateString;
    }

    public class LocalArguments {
        private final List<ToolArgs> arguments;

        private final String prompt;

        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
            this.arguments = arguments;
            this.prompt = prompt;
            this.context = context;
        }

        public String getAccessKey() {
            // Try to decrypt the value, otherwise assume it is a plain text value, and finally
            // fall back to the value defined in the local configuration.
            final Try<String> token = Try.of(() -> getTextEncryptor().decrypt(context.get("gong_access_key")))
                    .recover(e -> context.get("gong_access_key"))
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recoverWith(e -> Try.of(() -> getConfigAccessKey().get()));

            if (token.isFailure() || StringUtils.isBlank(token.get())) {
                throw new InternalFailure("Failed to get Gong access key");
            }

            return token.get();
        }

        public String getAccessSecretKey() {
            // Try to decrypt the value, otherwise assume it is a plain text value, and finally
            // fall back to the value defined in the local configuration.
            final Try<String> token = Try.of(() -> getTextEncryptor().decrypt(context.get("gong_access_secret_key")))
                    .recover(e -> context.get("gong_access_secret_key"))
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recoverWith(e -> Try.of(() -> getConfigAccessSecretKey().get()));

            if (token.isFailure() || StringUtils.isBlank(token.get())) {
                throw new InternalFailure("Failed to get Gong access secret key");
            }

            return token.get();
        }

        public int getDays() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigDays()::get,
                    arguments,
                    context,
                    Gong.DAYS_ARG,
                    "gong_days",
                    "0").value();

            return Try.of(() -> Integer.parseInt(stringValue))
                    .recover(throwable -> 0)
                    .map(i -> Math.max(0, i))
                    .get();
        }

        public String getStartDate() {
            return OffsetDateTime.now(ZoneId.systemDefault())
                    // truncate to the day to increase the chances of getting a cache hit
                    .truncatedTo(ChronoUnit.DAYS)
                    // Assume one day if nothing was specified
                    .minusDays(getDays())
                    .format(ISO_OFFSET_DATE_TIME);
        }
    }
}