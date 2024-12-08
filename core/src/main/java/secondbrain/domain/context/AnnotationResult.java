package secondbrain.domain.context;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Defines the result of an annotated document.
 *
 * @param result             The annotated document
 * @param annotationCoverage The ratio of annotations to the source context
 * @param context            The original context
 * @param <T>
 */
public record AnnotationResult<T>(String result, double annotationCoverage, T context) {
    /**
     * Returns the standard format of the annotated result.
     *
     * @param sourceItemsHeader The header of the source items, e.g. "Documents" or "Git Commits"
     * @param sourceItems       The source items
     * @param debugArgs         The optional debug args
     * @return The annotated result
     */
    public String getAnnotatedResult(final String sourceItemsHeader, final List<String> sourceItems, final String debugArgs) {
        return result()
                + System.lineSeparator() + System.lineSeparator()
                + sourceItemsHeader + ":" + System.lineSeparator()
                + String.join(System.lineSeparator(), sourceItems)
                + System.lineSeparator() + System.lineSeparator()
                + "Annotation Coverage: " + annotationCoverage()
                + (StringUtils.isBlank(debugArgs) ? "" : debugArgs);
    }
}
