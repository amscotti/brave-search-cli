package io.amscotti.bravesearch.application.exchange;

import java.util.Objects;

/** One query parameter; the name and the value are encoded independently. */
public record QueryParameter(String name, String value) {

    public QueryParameter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
    }
}
