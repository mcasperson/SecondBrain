package secondbrain.infrastructure.zendesk.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * This record represents a comment from a ZenDesk ticket with ID values replaced with their corresponding names.
 *
 * @param body   The content of the comment.
 * @param author The name of the author of the comment, which is a string instead of an ID.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ZenDeskProcessedCommentResponse(String body, String author) {
}
