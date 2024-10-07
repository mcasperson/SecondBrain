package secondbrain.infrastructure.github;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/repos")
public interface GitHub {
    @GET
    @Path("/{owner}/{repo}/commits")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    List<GitHubCommitResponse> getCommits(
            @PathParam("owner") final String owner,
            @PathParam("repo") final String repo,
            @QueryParam("sha") final String sha,
            @QueryParam("until") final String until,
            @QueryParam("since") final String since,
            @HeaderParam("Authorization") final String authorization);

    @GET
    @Path("/{owner}/{repo}/commits/{sha}.diff")
    String getDiff(
            @PathParam("owner") final String owner,
            @PathParam("repo") final String repo,
            @PathParam("sha") final String sha,
            @HeaderParam("Authorization") final String authorization);
}