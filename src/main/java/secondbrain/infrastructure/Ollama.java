package secondbrain.infrastructure;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import secondbrain.domain.tools.ToolCalling;

@Path("/api")
public interface Ollama {
    @POST
    @Path("/generate")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    OllamaResponse getTools(final OllamaGenerateBody body);
}