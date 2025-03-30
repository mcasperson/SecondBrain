package secondbrain.domain.tooldefs;

/**
 * Defines some meta details about the context collected for the entity.
 *
 * @param name  The name of the entity
 * @param value The count of context items collected for the entity
 */
public record MetaIntResult(String name, int value) {
}
