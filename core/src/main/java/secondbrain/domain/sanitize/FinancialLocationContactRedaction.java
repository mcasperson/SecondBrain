package secondbrain.domain.sanitize;

import ai.philterd.phileas.PhileasConfiguration;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.phileas.policy.Identifiers;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.policy.filters.*;
import ai.philterd.phileas.services.context.DefaultContextService;
import ai.philterd.phileas.services.filters.filtering.PlainTextFilterService;
import ai.philterd.phileas.services.strategies.rules.*;
import io.smallrye.common.annotation.Identifier;
import io.vavr.control.Try;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

@ApplicationScoped
@Identifier("financialLocationContactRedaction")
public class FinancialLocationContactRedaction implements SanitizeDocument {

    @Nullable
    private PlainTextFilterService filterService;

    @PostConstruct
    void construct() {
        this.filterService = new PlainTextFilterService(
                new PhileasConfiguration(new Properties()),
                new DefaultContextService(),
                null,
                null);
    }

    private Policy createPolicy() {
        final Policy policy = new Policy();

        final EmailAddressFilterStrategy emailStrategy = new EmailAddressFilterStrategy();
        emailStrategy.setStrategy("REDACT");
        emailStrategy.setRedactionFormat("{{{REDACTED-EMAIL}}}");

        final EmailAddress emailIdentifier = new EmailAddress();
        emailIdentifier.setEmailAddressFilterStrategies(List.of(emailStrategy));

        final IpAddressFilterStrategy ipStrategy = new IpAddressFilterStrategy();
        ipStrategy.setStrategy("REDACT");
        ipStrategy.setRedactionFormat("{{{REDACTED-IP}}}");

        final IpAddress ipIdentifier = new IpAddress();
        ipIdentifier.setIpAddressFilterStrategies(List.of(ipStrategy));

        final PhoneNumberFilterStrategy phoneNumberStrategy = new PhoneNumberFilterStrategy();
        phoneNumberStrategy.setStrategy("REDACT");
        phoneNumberStrategy.setRedactionFormat("{{{REDACTED-PHONE}}}");
        final PhoneNumber phoneNumberIdentifier = new PhoneNumber();
        phoneNumberIdentifier.setPhoneNumberFilterStrategies(List.of(phoneNumberStrategy));

        final StreetAddressFilterStrategy streetAddressStrategy = new StreetAddressFilterStrategy();
        streetAddressStrategy.setStrategy("REDACT");
        streetAddressStrategy.setRedactionFormat("{{{REDACTED-STREET-ADDRESS}}}");
        final StreetAddress streetAddressIdentifier = new StreetAddress();
        streetAddressIdentifier.setStreetAddressFilterStrategies(List.of(streetAddressStrategy));

        final CreditCardFilterStrategy creditCardStrategy = new CreditCardFilterStrategy();
        creditCardStrategy.setStrategy("REDACT");
        creditCardStrategy.setRedactionFormat("{{{REDACTED-CREDIT-CARD}}}");
        final CreditCard creditCardIdentifier = new CreditCard();
        creditCardIdentifier.setCreditCardFilterStrategies(List.of(creditCardStrategy));

        final SsnFilterStrategy ssnStrategy = new SsnFilterStrategy();
        ssnStrategy.setStrategy("REDACT");
        ssnStrategy.setRedactionFormat("{{{REDACTED-SSN}}}");
        final Ssn ssnIdentifier = new Ssn();
        ssnIdentifier.setSsnFilterStrategies(List.of(ssnStrategy));

        final BankRoutingNumberFilterStrategy bankRoutingNumberFilterStrategy = new BankRoutingNumberFilterStrategy();
        bankRoutingNumberFilterStrategy.setStrategy("REDACT");
        bankRoutingNumberFilterStrategy.setRedactionFormat("{{{REDACTED-BANK-ROUTING-NUMBER}}}");
        final BankRoutingNumber bankRoutingNumberIdentifier = new BankRoutingNumber();
        bankRoutingNumberIdentifier.setBankRoutingNumberFilterStrategies(Arrays.asList(bankRoutingNumberFilterStrategy));

        final UrlFilterStrategy urlFilterStrategy = new UrlFilterStrategy();
        urlFilterStrategy.setStrategy("REDACT");
        urlFilterStrategy.setRedactionFormat("{{{REDACTED-URL}}}");
        final Url urlIdentifier = new Url();
        urlIdentifier.setUrlFilterStrategies(List.of(urlFilterStrategy));

        final Identifiers identifiers = new Identifiers();
        identifiers.setEmailAddress(emailIdentifier);
        identifiers.setIpAddress(ipIdentifier);
        identifiers.setPhoneNumber(phoneNumberIdentifier);
        identifiers.setStreetAddress(streetAddressIdentifier);
        identifiers.setCreditCard(creditCardIdentifier);
        identifiers.setSsn(ssnIdentifier);
        identifiers.setBankRoutingNumber(bankRoutingNumberIdentifier);
        identifiers.setUrl(urlIdentifier);

        policy.setIdentifiers(identifiers);

        return policy;
    }

    @Override
    public @Nullable String sanitize(@Nullable final String document) {
        if (StringUtils.isBlank(document)) {
            return "";
        }

        final PlainTextFilterService service = filterService;
        if (service == null) {
            return document;
        }

        return Try.of(() -> service.filter(createPolicy(), "llm", document))
                .map(TextFilterResult::getFilteredText)
                // In the event of a failure, return the original document
                .recover(ex -> document)
                .get();
    }
}
