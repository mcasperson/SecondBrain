package secondbrain.application.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Application;
import secondbrain.domain.handler.PromptHandler;
import secondbrain.domain.json.JsonDeserializer;

import java.util.Map;

@ApplicationPath("/api")
public class RestApplication extends Application {
}