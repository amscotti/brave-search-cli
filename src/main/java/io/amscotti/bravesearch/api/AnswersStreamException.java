package io.amscotti.bravesearch.api;

import io.amscotti.bravesearch.domain.error.ClassifiedFailure;
import io.amscotti.bravesearch.domain.error.FailureKind;
import java.io.Serial;
import java.util.Objects;

/**
 * The terminal failure of a public Answers stream: delivered exactly once through
 * {@code onError} to the stream's single subscriber when the exchange failed after opening.
 *
 * <p>The exception carries a redacted diagnostic and the stable {@link #kind() failure
 * category} — the same vocabulary every blocking endpoint's outcome carries — so consumers
 * can categorize stream failures programmatically, and it never chains the internal cause:
 * internal failure types are an implementation detail this surface does not leak. A stream
 * that ends by cancellation or explicit close signals no failure at all, per the
 * reactive-streams convention.
 */
public final class AnswersStreamException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final FailureKind kind;

    /**
     * A stream failure of the given category.
     *
     * @throws NullPointerException when {@code diagnostic} or {@code kind} is null
     */
    public AnswersStreamException(String diagnostic, FailureKind kind) {
        super(Objects.requireNonNull(diagnostic, "diagnostic"));
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /**
     * A stream failure without a basis for a narrower category; reads as
     * {@link FailureKind#TRANSPORT}.
     *
     * @throws NullPointerException when {@code diagnostic} is null
     */
    public AnswersStreamException(String diagnostic) {
        this(diagnostic, FailureKind.TRANSPORT);
    }

    /** The stable failure category of this stream failure; never null. */
    public FailureKind kind() {
        return kind;
    }

    /**
     * The public projection of one internal terminal failure: the redacted diagnostic travels,
     * the internal type does not, and a failure that declares its own category keeps it while
     * anything else reads as a transport failure.
     */
    static AnswersStreamException of(Throwable source) {
        String diagnostic = source.getMessage();
        FailureKind kind =
                source instanceof ClassifiedFailure classified ? classified.kind() : FailureKind.TRANSPORT;
        return new AnswersStreamException(
                diagnostic == null ? "the answers stream ended in failure" : diagnostic, kind);
    }
}
