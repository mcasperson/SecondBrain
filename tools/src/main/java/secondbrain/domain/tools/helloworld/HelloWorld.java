package secondbrain.domain.tools.helloworld;

import com.google.common.collect.ImmutableList;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.FailedOllama;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;

import java.util.List;
import java.util.Map;

import static com.google.common.base.Predicates.instanceOf;

/**
 * A tool that returns a greeting message.
 */
@ApplicationScoped
public class HelloWorld implements Tool<Void> {
    @Override
    public String getName() {
        return HelloWorld.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns a greeting message";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(new ToolArguments(
                "greeting",
                "The greeting to display",
                "World"));
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        return List.of();
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> arguments.size() != 1 ? arguments.getFirst().argValue() : "Hello, World!")
                .map(RagMultiDocumentContext::new);

        /*
            It is the responsibility of the tool to return either an InternalFailure or an ExternalFailure.
            Typically, InternalFailure means that there is no point in retrying. ExternalFailure means that
            you might be able to retry and get a successful result.

            Handle mapFailure in isolation to avoid intellij making a mess of the formatting
            https://github.com/vavr-io/vavr/issues/2411
         */
        return result.mapFailure(
                        // Pass through any InternalFailure exceptions
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> new InternalFailure(throwable.getMessage(), throwable)),
                        // This is an example of an exception that may have been thrown while processing the result.
                        // We treat this as an internal exception.
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("Something was empty")),
                        // Everything else is treated as an external exception. We might be able to retry these.
                        API.Case(API.$(), throwable -> new ExternalFailure("An external service failed")))
                .get();
    }

    @Override
    public String getContextLabel() {
        return "Unused";
    }
}
