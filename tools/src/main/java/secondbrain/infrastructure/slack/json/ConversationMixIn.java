package secondbrain.infrastructure.slack.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonIgnoreProperties(ignoreUnknown = true)
@SuppressWarnings("NullAway")
abstract class ConversationMixIn {
    @JsonDeserialize(using = LenientIntegerDeserializer.class)
    Integer created;

    @JsonDeserialize(using = LenientIntegerDeserializer.class)
    Integer unlinked;

    @JsonDeserialize(using = LenientIntegerDeserializer.class)
    Integer numOfMembers;

    @JsonDeserialize(using = LenientIntegerDeserializer.class)
    Integer unreadCount;

    @JsonDeserialize(using = LenientIntegerDeserializer.class)
    Integer unreadCountDisplay;

    @JsonDeserialize(using = LenientIntegerDeserializer.class)
    Integer dateConnected;

    @JsonDeserialize(using = LenientIntegerDeserializer.class)
    Integer isMoved;
}
