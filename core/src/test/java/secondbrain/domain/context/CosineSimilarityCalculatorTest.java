package secondbrain.domain.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CosineSimilarityCalculatorTest {

    private static final double DELTA = 1e-9;

    private final CosineSimilarityCalculator calculator = new CosineSimilarityCalculator();

    @Test
    void identicalVectorsHaveSimilarityOne() {
        assertEquals(1.0, calculator.calculateSimilarity(new Vector(1.0, 2.0, 3.0), new Vector(1.0, 2.0, 3.0)), DELTA);
    }

    @Test
    void similarityIgnoresMagnitude() {
        assertEquals(1.0, calculator.calculateSimilarity(new Vector(1.0, 2.0), new Vector(3.0, 6.0)), DELTA);
    }

    @Test
    void orthogonalVectorsHaveSimilarityZero() {
        assertEquals(0.0, calculator.calculateSimilarity(new Vector(1.0, 0.0), new Vector(0.0, 5.0)), DELTA);
    }

    @Test
    void oppositeVectorsHaveSimilarityMinusOne() {
        assertEquals(-1.0, calculator.calculateSimilarity(new Vector(1.0, -2.0), new Vector(-2.0, 4.0)), DELTA);
    }

    @Test
    void similarityIsDotProductDividedByProductOfNorms() {
        // dot = 1*3 + 2*4 = 11, norms = sqrt(5) and 5
        assertEquals(11.0 / (Math.sqrt(5.0) * 5.0),
                calculator.calculateSimilarity(new Vector(1.0, 2.0), new Vector(3.0, 4.0)), DELTA);
    }

    @Test
    void zeroVectorProducesNaN() {
        assertTrue(calculator.calculateSimilarity(new Vector(0.0, 0.0), new Vector(1.0, 2.0)).isNaN());
    }
}
