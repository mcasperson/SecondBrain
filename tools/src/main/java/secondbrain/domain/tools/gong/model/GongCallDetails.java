package secondbrain.domain.tools.gong.model;

/**
 * This class represents the contract between a tool and the Gong API.
 *
 * @param id  The call ID
 * @param url The call URL
 */
public record GongCallDetails(String id, String url) {
}
