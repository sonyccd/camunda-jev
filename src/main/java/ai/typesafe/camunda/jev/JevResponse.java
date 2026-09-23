package ai.typesafe.camunda.jev;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * The Jev API response. Parsed leniently — unknown fields are ignored so that additive API changes
 * do not break the connector.
 */
public record JevResponse(String model, Map<String, Answer> answers, Usage usage) {

  /**
   * One answer. {@code confidence} and {@code probabilities} are documented as required for Choice,
   * but are boxed here so a malformed response surfaces as a clear error rather than as 0.0.
   */
  public record Answer(
      String type, String choice, Map<String, Double> probabilities, Double confidence) {}

  public record Usage(
      @JsonProperty("input_tokens") Integer inputTokens,
      @JsonProperty("output_tokens") Integer outputTokens) {}
}
