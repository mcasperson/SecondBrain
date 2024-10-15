package secondbrain.infrastructure.oauth.slack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/*
The format of the response is this:
{
  "ok" : true,
  "app_id" : "xxx",
  "authed_user" : {
    "id" : "xxx",
    "scope" : "channels:history,channels:read,search:read",
    "access_token" : "xoxp-xxx",
    "token_type" : "user"
  },
  "team" : {
    "id" : "xxx",
    "name" : "Team name"
  },
  "enterprise" : null,
  "is_enterprise_install" : false
}
 */

@JsonIgnoreProperties(ignoreUnknown = true)
public record SlackOauthTokenResponse(SlackOauthAuthedUser authed_user) {
}
