package secondbrain.domain.injection;

import jakarta.inject.Qualifier;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * From https://www.cdi-spec.org/faq/
 * The quickest, easiest, and preferred way to be explicit is to assign a qualifier to the producer.
 * By doing so, you are being more specific about which one you want the container to use.
 */
@Target({TYPE, METHOD, PARAMETER, FIELD})
@Retention(RUNTIME)
@Documented
@Qualifier
public @interface Preferred {
}