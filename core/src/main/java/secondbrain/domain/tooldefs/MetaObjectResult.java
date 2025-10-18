package secondbrain.domain.tooldefs;

import org.jspecify.annotations.Nullable;

/**
 * Defines some source details about the context collected for the entity.
 *
 * @param name   The name of the entity
 * @param value  The context items collected for the entity
 * @param id     The ID of the entity associated with the metadata
 * @param source The source system of the entity (e.g., "Zendesk", "Gong")
 */
public record MetaObjectResult(String name, Object value, @Nullable String id, @Nullable String source) {
    public MetaObjectResult(final String name, final Object value) {
        this(name, value, null, null);
    }
}
