package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import io.amscotti.bravesearch.adapter.bravehttp.json.UpstreamJsonCodec;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * The web endpoint's continuation rule over one completed body: the run may request another
 * page only while the top-level {@code query.more_results_available} member reads exactly
 * boolean {@code true}.
 *
 * <p>Everything else stops after the page — a false flag, an absent member, a non-boolean
 * value, a body whose {@code query} member is not an object, and a body that is not
 * readable JSON at all — because the tolerant read must fail toward the safe side: a
 * misread continuation would burst the user's quota, while an early stop only ends the run.
 * Lookalikes nested anywhere but the top-level {@code query} object never continue the run,
 * and neither does the flag's spelling occurring inside some other member's text.
 */
public final class WebContinuationProbe implements PaginationService.ContinuationProbe {

    @Override
    public boolean moreResultsAvailable(UpstreamPayload body) {
        JsonNode root;
        try {
            root = UpstreamJsonCodec.readFirstDocument(body.toByteArray());
        } catch (JacksonException unreadable) {
            return false;
        }
        if (root == null || !root.isObject()) {
            return false;
        }
        JsonNode flag = root.path("query").path("more_results_available");
        return flag.isBoolean() && flag.booleanValue();
    }
}
