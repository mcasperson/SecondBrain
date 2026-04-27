package secondbrain.domain.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Describes a property, typically on a record, that is exposed to the LLM context.
 * This is used when dumping a raw data object into the context, to provide a human-readable label for the property.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface PropertyLabel {
    String description();
}
