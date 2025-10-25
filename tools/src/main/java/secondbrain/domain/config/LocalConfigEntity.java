package secondbrain.domain.config;

/**
 * Represents a configuration that includes an entity identifier.
 */
public interface LocalConfigEntity {
    /**
     * Gets the entity identifier. This is typically used to embed the name of the entity
     * into sentences vectors to more closely match the source content from a named entity
     * to the vector representation when mixed with other vectors from different entities.
     *
     * @return the entity identifier as a String
     */
    String getEntity();
}
