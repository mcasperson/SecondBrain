package secondbrain.infrastructure.planhat.api;

import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

/**
 * An individual interaction that makes up a PlanHat conversation, such as an email in an email
 * conversation or a comment in a ticket. The different kinds of interaction are processed through
 * the same pipeline, so they share a common interface.
 */
public interface PlanHatActivity extends TextData, IdData, UrlData {
}
