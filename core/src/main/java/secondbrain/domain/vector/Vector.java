package secondbrain.domain.vector;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Represents a vector of double values.
 * @param value The list of double values
 */
public record Vector(List<Double> value) {

    public Vector(final Double... value) {
        this(Arrays.asList(value));
    }

    public Vector(final double[] values) {
        this(Arrays.stream(values).boxed().toList());
    }

    public Vector(final List<Double> value) {
        this.value = Objects.requireNonNull(value);
    }

    public int dimension() {
        return value.size();
    }

    public double get(final int index) {
        if (index < 0 || index >= value.size()) {
            throw new IndexOutOfBoundsException("Index: " + index + " is out of bound");
        }
        return value.get(index);
    }

    public double dotProduct(final Vector other) {
        double dotProduct = 0.0;

        for (int i = 0; i < value.size(); i++) {
            dotProduct += this.get(i) * other.get(i);
        }
        return dotProduct;
    }

    public double norm() {
        return Math.sqrt(
                value.stream()
                        .mapToDouble(element -> Math.pow(element, 2))
                        .sum()
        );
    }


}