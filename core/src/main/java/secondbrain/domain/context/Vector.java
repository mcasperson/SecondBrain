package secondbrain.domain.context;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents a vector of double values backed by a primitive double array
 * to minimize heap usage (avoids boxing each value as a Double object).
 *
 * @param value The primitive double array
 */
public record Vector(double[] value) {

    public Vector(final Double... value) {
        this(Arrays.stream(value).mapToDouble(Double::doubleValue).toArray());
    }

    public Vector(final double[] value) {
        this.value = Objects.requireNonNull(value);
    }

    public int dimension() {
        return value.length;
    }

    public double get(final int index) {
        if (index < 0 || index >= value.length) {
            throw new IndexOutOfBoundsException("Index: " + index + " is out of bound");
        }
        return value[index];
    }

    public double dotProduct(final Vector other) {
        double dotProduct = 0.0;

        for (int i = 0; i < value.length; i++) {
            dotProduct += value[i] * other.value[i];
        }
        return dotProduct;
    }

    public double norm() {
        double sum = 0.0;
        for (final double v : value) {
            sum += v * v;
        }
        return Math.sqrt(sum);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof Vector other)) return false;
        return Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "Vector[value=" + Arrays.toString(value) + "]";
    }
}