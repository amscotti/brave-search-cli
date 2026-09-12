/**
 * Transport-agnostic vocabulary of one Brave API exchange: the origin policy with its
 * credential routing, the composed request and its deterministic header assembly, the
 * composed request URI with its redacted rendering, and the completed response. Everything
 * here is pure value or policy over {@code java.net} types; the wire technology itself lives
 * in the HTTP adapter.
 */
package io.amscotti.bravesearch.application.exchange;
