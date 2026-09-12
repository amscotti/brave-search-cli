/**
 * The public library surface of {@code io.amscotti:brave-search-client}: the
 * {@link io.amscotti.bravesearch.api.BraveSearchClient} facade with one method per Brave
 * endpoint, the endpoint response types with their caller-owned lossless upstream
 * snapshots, and the streaming Answers surface — {@link io.amscotti.bravesearch.api.AnswersStreamHandle},
 * its projected {@link io.amscotti.bravesearch.api.PublicStreamFrame} frames, and
 * {@link io.amscotti.bravesearch.api.StreamCancellation}.
 *
 * <p>Composition runs through {@code BraveSearchClient.builder()}; the public constructor
 * exists for the composition and for callers wiring their own endpoints, and every
 * collaborator it takes is a plain function over public, domain, or JDK types — no internal
 * type appears in any public signature. The durable contract of this package is
 * {@code docs/java-api.md}; everything in this package except {@code api.internal..} is
 * covered by the semantic-versioning policy described there.
 */
package io.amscotti.bravesearch.api;
