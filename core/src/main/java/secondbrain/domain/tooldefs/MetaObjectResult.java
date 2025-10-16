package secondbrain.domain.tooldefs;

/**
 * Defines some source details about the context collected for the entity.
 *
 * @param name   The name of the entity
 * @param value  The context items collected for the entity
 * @param id     The ID of the entity associated with the metadata
 * @param source The source system of the entity (e.g., "Zendesk", "Gong")
 */
public record MetaObjectResult(String name, Object value, String id, String source) {
}
