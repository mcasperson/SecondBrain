package secondbrain.domain.vector;

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Use the Java Deep Learning library to vectorize sentences.
 */
@ApplicationScoped
public class JdlSentenceVectorizer implements SentenceVectorizer, AutoCloseable {
    private static final String DJL_MODEL = "sentence-transformers/all-MiniLM-L6-v2";
    private static final String DJL_PATH = "djl://ai.djl.huggingface.pytorch/" + DJL_MODEL;

    private Predictor<String, float[]> predictor;

    public JdlSentenceVectorizer() {
        this.predictor = Try.of(() -> Criteria.builder()
                .setTypes(String.class, float[].class)
                .optModelUrls(DJL_PATH)
                .optEngine("PyTorch")
                .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
                .optProgress(new ProgressBar())
                .build())
                .mapTry(Criteria::loadModel)
                .mapTry(ZooModel::newPredictor)
                .onFailure((Throwable e) -> System.err.println("Failed to initialise predictor: " + ExceptionUtils.getRootCause(e)))
                .getOrElse(() -> null);
    }

    public RagStringContext vectorize(final String text) {
        if (predictor == null) {
            throw new RuntimeException("Predictor is not initialized");
        }

        return Try.of(() -> predictor.predict(text))
                .map(embeddings -> new Vector(floatToDouble(embeddings)))
                .map(vector -> new RagStringContext(text, vector))
                .getOrElseThrow((Throwable e) -> new RuntimeException("Error while getting embeddings", e));
    }

    private double[] floatToDouble(final float[] values) {
        double[] doubleArray = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            doubleArray[i] = (double) values[i];
        }
        return doubleArray;
    }

    @Override
    public void close() throws Exception {
        predictor.close();
    }
}
