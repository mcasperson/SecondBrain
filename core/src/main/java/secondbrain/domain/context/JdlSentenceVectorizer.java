package secondbrain.domain.context;

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import io.vavr.control.Try;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.persist.LocalStorage;

import java.util.List;
import java.util.logging.Logger;

/**
 * Use the Java Deep Learning library to vectorize sentences.
 */
@ApplicationScoped
public class JdlSentenceVectorizer implements SentenceVectorizer, AutoCloseable {
    private static final int TTL_SECONDS = 60 * 60 * 24 * 90;
    // https://www.sbert.net/docs/sentence_transformer/pretrained_models.html
    private static final String DJL_MODEL = "sentence-transformers/all-MiniLM-L12-v2";
    private static final String DJL_PATH = "djl://ai.djl.huggingface.pytorch/" + DJL_MODEL;

    private Predictor<String, float[]> predictor;

    @Inject
    private Logger logger;

    @Inject
    @Preferred
    private LocalStorage localStorage;

    @PostConstruct
    private void init() {
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

    @PreDestroy
    private void destroy() {
        close();
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

        return localStorage.getOrPutObject(JdlSentenceVectorizer.class.getSimpleName(),
                "vectorize",
                DigestUtils.sha256Hex(text + hiddenText + DJL_PATH),
                TTL_SECONDS,
                RagStringContext.class,
                () -> vectorizeApi(text, hiddenText)).result();
    }

    private RagStringContext vectorizeApi(final String text, final @Nullable String hiddenText) {
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
    public void close() {
        if (predictor == null) {
            return;
        }

        Try.run(() -> predictor.close())
                .onFailure(ex -> logger.warning("Failed to close predictor: " + ExceptionUtils.getRootCause(ex)));
    }
}
