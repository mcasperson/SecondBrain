package secondbrain.domain.tools.slackzengoogle;

/**
 * Defines some meta details about the context collected for the entity.
 *
 * @param name         The name of the entity
 * @param contextCount The count of context items collected for the entity
 */
public record MetaResult(String name, int contextCount) {
}
