package secondbrain.domain.context;

/**
 * Defines the result of an annotated document.
 *
 * @param result             The annotated document
 * @param annotationCoverage The ratio of annotations to the source context
 * @param context            The original context
 * @param <T>
 */
public record AnnotationResult<T>(String result, double annotationCoverage, T context) {
}
