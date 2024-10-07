package secondbrain.infrastructure.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@ApplicationScoped
public class GitHubClient {
    public List<GitHubCommitResponse> getCommits(Client client, String owner, String repo, String sha, String until, String since, String authorization) {
        try(final Response response = client.target("https://api.github.com/repos/" + owner + "/" + repo + "/commits")
                .queryParam("sha", sha)
                .queryParam("until", until)
                .queryParam("since", since)
                .request()
                .header("Authorization", authorization)
                .header("Accept", MediaType.APPLICATION_JSON)
                .get()) {
            return List.of(response.readEntity(GitHubCommitResponse[].class));
        }
    }

    public String getDiff(Client client, String owner, String repo, String sha, String authorization) {
        return client.target("https://api.github.com/repos/" + owner + "/" + repo + "/commits/" + sha)
                .request()
                .header("Authorization", authorization)
                .header("Accept", "application/vnd.github.v3.diff")
                .get(String.class);
    }
}
