package secondbrain.domain.toolbuilder.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.toolbuilder.ToolBuilderLlama3;
import secondbrain.domain.tools.TestTool;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ToolBuilderLlama3Test {

    @Test
    public void testBuildToolJson() throws JsonProcessingException {
        final ToolBuilderLlama3 builder = new ToolBuilderLlama3();
        final String json = builder.buildToolJson(List.of(new TestTool()));

        final String expectedJson = """
                {
                    "name": "Test",
                    "description": "Test Tool",
                    "parameters": {
                        "arg1": {
                            "type": "String",
                            "description": "description1",
                            "default": "default1"
                        },
                        "arg2": {
                            "type": "String",
                            "description": "description2",
                            "default": "default2"
                        }
                    }
                }
                """;

        final Map<String, Object> expectedJsonMap = new JsonDeserializerJackson().deserializeMap(expectedJson, String.class, Object.class);
        final Map<String, Object> jsonMap = new JsonDeserializerJackson().deserializeMap(json, String.class, Object.class);

        assertEquals(expectedJsonMap, jsonMap);
    }
}