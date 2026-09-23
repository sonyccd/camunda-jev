package ai.typesafe.camunda.jev;

import java.util.Map;

/**
 * Connector output, published to the result variable (default {@code jevResult}).
 *
 * @param choice what the gateway branches on: an option name, or {@code "undecided"}
 * @param decided false when confidence fell below the threshold
 * @param confidence Jev's confidence, always the raw value even when undecided
 * @param jevChoice Jev's raw pick, kept even when undecided, for debugging and threshold tuning
 * @param probabilities every option mapped to its probability
 * @param model the resolved model version that answered
 */
public record JevResult(
    String choice,
    boolean decided,
    double confidence,
    String jevChoice,
    Map<String, Double> probabilities,
    String model) {}
