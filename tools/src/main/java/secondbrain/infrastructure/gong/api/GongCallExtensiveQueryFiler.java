package secondbrain.infrastructure.gong.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * https://gong.app.gong.io/settings/api/documentation#post-/v2/calls/extensive
 *
 * @param fromDateTime   The start date and time of the calls to retrieve
 * @param toDateTime     The end date and time of the calls to retrieve
 * @param primaryUserIds The user IDs of the primary users to retrieve calls for
 * @param callIds        The call IDs to retrieve
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallExtensiveQueryFiler(@Nullable String fromDateTime, @Nullable String toDateTime,
                                          @Nullable List<String> primaryUserIds,
                                          @Nullable List<String> callIds) {
}
