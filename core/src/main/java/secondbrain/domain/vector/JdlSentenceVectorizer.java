package secondbrain.domain.vector;

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.Map;

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
                .getOrElseThrow((Throwable e) -> new RuntimeException("Error while loading model", e));
    }

    private Map<String, String> getDJLConfig() {
        final Map<String, String> options = new HashMap<String, String>();
        options.put("addSpecialTokens", "false");
        options.put("padding", "false");
        options.put("modelMaxLength", "100000");
        options.put("maxLength", "100000");
        return options;
    }

    public RagStringContext vectorize(final String text) {
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
