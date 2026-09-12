package io.amscotti.bravesearch.domain.error;

/**
 * A failure that knows its own stable category. Typed failures of the streaming machinery
 * implement this so the public boundary can surface a programmatically readable
 * {@link FailureKind} without any internal type crossing it: the category is domain
 * vocabulary, the failure class stays behind the boundary.
 */
public interface ClassifiedFailure {

    /** The stable failure category of this failure. */
    FailureKind kind();
}
