package secondbrain.domain.tooldefs;

/**
 * Defines some source details about the context collected for the entity.
 *
 * @param name  The name of the entity
 * @param value The source value
 */
public record MetaStringResult(String name, String value) {
}
