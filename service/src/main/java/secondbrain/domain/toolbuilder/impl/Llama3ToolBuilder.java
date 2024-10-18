package secondbrain.domain.toolbuilder.impl;

import jakarta.enterprise.context.Dependent;
import org.jspecify.annotations.NonNull;
import secondbrain.domain.toolbuilder.ToolBuilder;
import secondbrain.domain.tooldefs.Tool;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A tool builder tailored for Llama3. Based on https://www.llama.com/docs/model-cards-and-prompt-formats/llama3_2/.
 */
@Dependent
public class Llama3ToolBuilder implements ToolBuilder {
    @Override
    public String buildToolJson(@NonNull final List<Tool> tools) {
        return tools.stream().map(tool -> """
                {
                    "name": "%s",
                    "description": "%s",
                    "parameters": {
                        %s
                    }
                }
                """.formatted(
                tool.getName(),
                tool.getDescription(),
                tool.getArguments().stream().map(argument -> """
                        "%s": {
                            "type": "%s",
                            "description": "%s",
                            "default": "%s"
                        }
                        """.formatted(
                        argument.name(),
                        "String",
                        argument.description(),
                        argument.defaultValue()
                )).collect(Collectors.joining(","))
        )).collect(Collectors.joining(","));
    }

    @Override
    public String buildToolPrompt(@NonNull final List<Tool> tools, @NonNull final String prompt) {
        return """
                <|begin_of_text|>
                <|start_header_id|>system<|end_header_id|>
                You are an expert in composing functions. You are given a question and a set of possible functions.
                Based on the question, you will need to make one or more function/tool calls to achieve the purpose.
                If none of the functions can be used, point it out. If the given question lacks the parameters required by the function,also point it out. You should only return the function call in tools call sections.
                If you decide to invoke any of the function(s), you MUST put it in the JSON format of 
                [{"toolName": "tool_name_1", "toolArgs": [{"argName": "arg_name_1", "argValue": "arg_value_1"}, {argName: "arg_name_2", argValue: "arg_value_2"}]}]
                You SHOULD NOT include any other text in the response.
                Here is a list of functions in JSON format that you can invoke. ["""
                + buildToolJson(tools) +
                """
                        ]
                        <|eot_id|><|start_header_id|>user<|end_header_id|>"""
                + prompt
                + "<|eot_id|><|start_header_id|>assistant<|end_header_id|>";
    }
}
