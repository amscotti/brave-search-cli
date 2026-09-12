package io.amscotti.example.bravesearch;

import io.amscotti.bravesearch.api.BraveSearchClient;
import io.amscotti.bravesearch.api.WebSearchResponse;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import java.nio.charset.StandardCharsets;

/**
 * An illustrative consumer of the published {@code io.amscotti:brave-search-client}
 * artifact: reads its API token from the environment — a policy this sample chooses for
 * itself, because the library never reads the environment on a consumer's behalf — builds
 * a client through the supported builder, and prints one web search's typed metadata and
 * upstream snapshot.
 *
 * <p>The run status is the process exit status, so scripts can tell the outcomes apart: 2
 * when the token is missing or blank, 1 when the search fails, and 0 after a successful
 * search.
 */
public final class SampleMain {

  public static void main(String[] args) {
    System.exit(run(System.getenv("BRAVE_API_KEY")));
  }

  static int run(String token) {
    if (token == null || token.isBlank()) {
      System.err.println("set BRAVE_API_KEY to the subscription token before running this sample");
      return 2;
    }
    try (BraveSearchClient client = BraveSearchClient.builder()
        .tokenSupplier(() -> Credential.of(token.getBytes(StandardCharsets.UTF_8)))
        .build()) {

      Outcome<WebSearchResponse> outcome =
          client.webSearch(WebSearchRequest.builder("brave search api").count(5).build());

      return switch (outcome) {
        case Outcome.Success<WebSearchResponse> success -> {
          WebSearchResponse response = success.value();
          System.out.println("status: " + response.httpStatus());
          System.out.println(
              "results: " + response.upstream().path("web").path("results").size());
          yield 0;
        }
        case Outcome.Failure<WebSearchResponse> failure -> {
          System.err.println("search failed: " + failure.kind() + ": " + failure.diagnostic());
          yield 1;
        }
      };
    }
  }

  private SampleMain() {}
}
