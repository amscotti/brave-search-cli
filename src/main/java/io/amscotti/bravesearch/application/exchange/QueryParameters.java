package io.amscotti.bravesearch.application.exchange;

import java.util.List;

/**
 * The optional-member write rules every GET endpoint assembles its query with: a member
 * is appended only when its value was supplied, so an unsupplied value is omitted from
 * the wire instead of coerced to an upstream default. The rules lived as one private
 * copy per endpoint assembler until the spellings had to be shared; this is the single
 * home now.
 */
public final class QueryParameters {

    private QueryParameters() {}

    /** Appends the string member {@code name} only when {@code value} was supplied. */
    public static void addString(List<QueryParameter> parameters, String name, String value) {
        if (value != null) {
            parameters.add(new QueryParameter(name, value));
        }
    }

    /** Appends the integer member {@code name} only when {@code value} was supplied. */
    public static void addNumber(List<QueryParameter> parameters, String name, Integer value) {
        if (value != null) {
            parameters.add(new QueryParameter(name, value.toString()));
        }
    }

    /** Appends the boolean member {@code name} only when {@code supplied} state was set. */
    public static void addFlag(List<QueryParameter> parameters, String name, Boolean supplied) {
        if (supplied != null) {
            parameters.add(new QueryParameter(name, supplied.toString()));
        }
    }
}
