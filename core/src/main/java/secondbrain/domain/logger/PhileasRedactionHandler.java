package secondbrain.domain.logger;

import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.phileas.policy.Identifiers;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.policy.filters.*;
import ai.philterd.phileas.services.filters.filtering.PlainTextFilterService;
import ai.philterd.phileas.services.strategies.dynamic.FirstNameFilterStrategy;
import ai.philterd.phileas.services.strategies.dynamic.SurnameFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.*;

import java.util.Arrays;
import java.util.logging.*;

public class PhileasRedactionHandler extends Handler {
    private final Handler delegate;
    private final PlainTextFilterService filterService;

    public PhileasRedactionHandler(Handler delegate, PlainTextFilterService filterService) {
        this.delegate = delegate;
        this.filterService = filterService;
    }

    @Override
    public void publish(LogRecord record) {
        try {
            // 1. Redact the message text
            TextFilterResult response = filterService.filter(createPolicy(), "jul-context", record.getMessage());
            record.setMessage(response.getFilteredText());

            // 2. Pass the redacted record to the actual output handler
            delegate.publish(record);
        } catch (Exception e) {
            reportError("Redaction failed", e, ErrorManager.WRITE_FAILURE);
        }
    }

    @Override
    public void flush() { delegate.flush(); }

    @Override
    public void close() throws SecurityException { delegate.close(); }

    private Policy createPolicy() {
        final Policy policy = new Policy();

        final EmailAddressFilterStrategy emailStrategy = new EmailAddressFilterStrategy();
        emailStrategy.setStrategy("REDACT");
        emailStrategy.setRedactionFormat("{{{REDACTED-EMAIL}}}");

        final EmailAddress emailIdentifier = new EmailAddress();
        emailIdentifier.setEmailAddressFilterStrategies(Arrays.asList(emailStrategy));

        final IpAddressFilterStrategy ipStrategy = new IpAddressFilterStrategy();
        ipStrategy.setStrategy("REDACT");
        ipStrategy.setRedactionFormat("{{{REDACTED-IP}}}");

        final IpAddress ipIdentifier = new IpAddress();
        ipIdentifier.setIpAddressFilterStrategies(Arrays.asList(ipStrategy));

        final FirstNameFilterStrategy firstNameFilterStrategy = new FirstNameFilterStrategy();
        firstNameFilterStrategy.setStrategy("REDACT");
        firstNameFilterStrategy.setRedactionFormat("{{{REDACTED-FIRST-NAME}}}");
        final FirstName firstNameIdentifier = new FirstName();
        firstNameIdentifier.setFirstNameFilterStrategies(Arrays.asList(firstNameFilterStrategy));

        final SurnameFilterStrategy surnameStrategy = new SurnameFilterStrategy();
        surnameStrategy.setStrategy("REDACT");
        surnameStrategy.setRedactionFormat("{{{REDACTED-SURNAME}}}");
        final Surname surnameIdentifier = new Surname();
        surnameIdentifier.setSurnameFilterStrategies(Arrays.asList(surnameStrategy));

        final PhoneNumberFilterStrategy phoneNumberStrategy = new PhoneNumberFilterStrategy();
        phoneNumberStrategy.setStrategy("REDACT");
        phoneNumberStrategy.setRedactionFormat("{{{REDACTED-PHONE}}}");
        final PhoneNumber phoneNumberIdentifier = new PhoneNumber();
        phoneNumberIdentifier.setPhoneNumberFilterStrategies(Arrays.asList(phoneNumberStrategy));

        final StreetAddressFilterStrategy streetAddressStrategy = new StreetAddressFilterStrategy();
        streetAddressStrategy.setStrategy("REDACT");
        streetAddressStrategy.setRedactionFormat("{{{REDACTED-STREET-ADDRESS}}}");
        final StreetAddress streetAddressIdentifier = new StreetAddress();
        streetAddressIdentifier.setStreetAddressFilterStrategies(Arrays.asList(streetAddressStrategy));

        final CreditCardFilterStrategy creditCardStrategy = new CreditCardFilterStrategy();
        creditCardStrategy.setStrategy("REDACT");
        creditCardStrategy.setRedactionFormat("{{{REDACTED-CREDIT-CARD}}}");
        final CreditCard creditCardIdentifier = new CreditCard();
        creditCardIdentifier.setCreditCardFilterStrategies(Arrays.asList(creditCardStrategy));

        final SsnFilterStrategy ssnStrategy = new SsnFilterStrategy();
        ssnStrategy.setStrategy("REDACT");
        ssnStrategy.setRedactionFormat("{{{REDACTED-SSN}}}");
        final Ssn ssnIdentifier = new Ssn();
        ssnIdentifier.setSsnFilterStrategies(Arrays.asList(ssnStrategy));

        final DateFilterStrategy dateFilterStrategy = new DateFilterStrategy();
        dateFilterStrategy.setStrategy("REDACT");
        dateFilterStrategy.setRedactionFormat("{{{REDACTED-DATE}}}");
        final Date dateIdentifier = new Date();
        dateIdentifier.setDateFilterStrategies(Arrays.asList(dateFilterStrategy));

        final BankRoutingNumberFilterStrategy bankRoutingNumberFilterStrategy = new BankRoutingNumberFilterStrategy();
        bankRoutingNumberFilterStrategy.setStrategy("REDACT");
        bankRoutingNumberFilterStrategy.setRedactionFormat("{{{REDACTED-BANK-ROUTING-NUMBER}}}");
        final BankRoutingNumber bankRoutingNumberIdentifier = new BankRoutingNumber();
        bankRoutingNumberIdentifier.setBankRoutingNumberFilterStrategies(Arrays.asList(bankRoutingNumberFilterStrategy));

        final UrlFilterStrategy urlFilterStrategy = new UrlFilterStrategy();
        urlFilterStrategy.setStrategy("REDACT");
        urlFilterStrategy.setRedactionFormat("{{{REDACTED-URL}}}");
        final Url urlIdentifier = new Url();
        urlIdentifier.setUrlFilterStrategies(Arrays.asList(urlFilterStrategy));

        final Identifiers identifiers = new Identifiers();
        identifiers.setEmailAddress(emailIdentifier);
        identifiers.setIpAddress(ipIdentifier);
        identifiers.setFirstName(firstNameIdentifier);
        identifiers.setSurname(surnameIdentifier);
        identifiers.setPhoneNumber(phoneNumberIdentifier);
        identifiers.setStreetAddress(streetAddressIdentifier);
        identifiers.setCreditCard(creditCardIdentifier);
        identifiers.setSsn(ssnIdentifier);
        identifiers.setDate(dateIdentifier);
        identifiers.setBankRoutingNumber(bankRoutingNumberIdentifier);
        identifiers.setUrl(urlIdentifier);

        policy.setIdentifiers(identifiers);

        return policy;
    }
}