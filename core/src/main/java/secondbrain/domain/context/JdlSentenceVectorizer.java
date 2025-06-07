package secondbrain.domain.context;

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.exceptions.InternalFailure;

import java.util.List;

/**
 * Use the Java Deep Learning library to vectorize sentences.
 */
@ApplicationScoped
public class JdlSentenceVectorizer implements SentenceVectorizer, AutoCloseable {
    private static final String DJL_MODEL = "sentence-transformers/all-MiniLM-L6-v2";
    private static final String DJL_PATH = "djl://ai.djl.huggingface.pytorch/" + DJL_MODEL;

    private final Predictor<String, float[]> predictor;

    public JdlSentenceVectorizer() {
        this.predictor = Try.of(() -> Criteria.builder()
                        .setTypes(String.class, float[].class)
                        .optModelUrls(DJL_PATH)
                        .optEngine("PyTorch")
                        .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
                        .build())
                .mapTry(Criteria::loadModel)
                .mapTry(ZooModel::newPredictor)
                .onFailure((Throwable e) -> System.err.println("Failed to initialise predictor: " + ExceptionUtils.getRootCause(e)))
                .getOrElse(() -> null);
    }

    public RagStringContext vectorize(final String text) {
        return vectorize(text, null);
    }

    @Override
    public List<RagStringContext> vectorize(final List<String> text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        return text.stream()
                .map(this::vectorize)
                .toList();
    }

    public RagStringContext vectorize(final String text, final @Nullable String hiddenText) {
        if (predictor == null) {
            throw new InternalFailure("Predictor is not initialized");
        }

        final String prefix = hiddenText == null ? "" : hiddenText + " ";

        return Try.of(() -> predictor.predict(prefix + text))
                .map(embeddings -> new Vector(floatToDouble(embeddings)))
                .map(vector -> new RagStringContext(text, vector))
                .getOrElseThrow((Throwable e) -> new InternalFailure("Error while getting embeddings", e));
    }

    @Override
    public List<RagStringContext> vectorize(final List<String> text, final String hiddenText) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        return text.stream()
                .map(t -> vectorize(t, hiddenText))
                .toList();
    }

    private double[] floatToDouble(final float[] values) {
        double[] doubleArray = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            doubleArray[i] = values[i];
        }
        return doubleArray;
    }

    @Override
    public void close() throws Exception {
        predictor.close();
    }
}
