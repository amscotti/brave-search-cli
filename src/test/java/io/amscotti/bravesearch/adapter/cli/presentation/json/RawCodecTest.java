package io.amscotti.bravesearch.adapter.cli.presentation.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Raw mode emits the exact decoded upstream bytes with no added framing. */
final class RawCodecTest {

    @Test
    void passthroughIsByteIdenticalToTheDecodedBody() {
        byte[] decoded = {0x00, 0x01, 0x0a, (byte) 0xff, '{', '}'};
        assertArrayEquals(decoded, new RawCodec().passthrough(new UpstreamPayload(decoded)));
    }

    @Test
    void passthroughAddsNoTrailingNewline() {
        byte[] decoded = "{\"web\":{\"results\":[]}}".getBytes(StandardCharsets.UTF_8);
        byte[] out = new RawCodec().passthrough(new UpstreamPayload(decoded));
        assertEquals(decoded.length, out.length);
        assertArrayEquals(decoded, out);
    }
}
