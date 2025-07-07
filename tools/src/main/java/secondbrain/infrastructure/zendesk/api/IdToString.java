package secondbrain.infrastructure.zendesk.api;

/**
 * Interface to convert an ID to a String representation.
 * This is used in the Zendesk API context to map IDs to their string representations.
 */
public interface IdToString {
    String getStringFromId(Long id);
}
