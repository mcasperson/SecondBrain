package secondbrain.domain.tooldefs;

/**
 * Defines some source details about the context collected for the entity.
 *
 * @param name  The name of the entity
 * @param value The context items collected for the entity
 */
public record MetaObjectResult(String name, Object value) {
}
