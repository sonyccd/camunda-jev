package ai.typesafe.camunda.jev;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Checks the connector against the real Jev API. Excluded from the normal build: it costs tokens
 * and needs a key. Run it with {@code ./jev smoke}.
 *
 * <p>Its job is to catch the thing mocks cannot — that the request shape, auth and response parsing
 * still match the live service.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "TYPESAFE_API_KEY", matches = ".+")
class LiveJevTest {

  private static JevRequest request(String state, double threshold) {
    return new JevRequest(
        System.getenv("TYPESAFE_API_KEY"),
        state,
        "Which team should handle this?",
        TestSupport.OPTIONS,
        threshold,
        null);
  }

  @Test
  void realApiAnswersWithAChoiceFromTheSuppliedOptions() {
    JevResponse response = new JevClient().call(request("My payouts have been failing.", 0.7));
    JevResponse.Answer answer = response.answers().get(JevClient.QUESTION_KEY);

    assertThat(answer).as("answer under the 'route' key").isNotNull();
    assertThat(answer.type()).isEqualTo("choice");
    assertThat(answer.choice()).isIn("billing", "technical", "sales");
    assertThat(answer.confidence()).isBetween(0.0, 1.0);
    assertThat(answer.probabilities()).containsOnlyKeys("billing", "technical", "sales");
    // Probabilities are documented to sum to 1.
    assertThat(answer.probabilities().values().stream().mapToDouble(Double::doubleValue).sum())
        .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.02));
    // The response reports the resolved version, not the alias that was sent.
    assertThat(response.model()).isNotBlank();
  }

  @Test
  void structuredStateIsAcceptedAsNativeJson() {
    // The whole reason state is typed Object: confirm the live API really does take an object.
    Map<String, Object> ticket =
        Map.of(
            "subject", "Payout failures",
            "body", "Our scheduled payouts have failed three days running.",
            "tier", "enterprise");

    JevRequest structured =
        new JevRequest(
            System.getenv("TYPESAFE_API_KEY"),
            ticket,
            "Which team should handle this?",
            TestSupport.OPTIONS,
            0.7,
            null);

    JevResponse.Answer answer =
        new JevClient().call(structured).answers().get(JevClient.QUESTION_KEY);

    assertThat(answer).isNotNull();
    assertThat(answer.choice()).isIn("billing", "technical", "sales");
  }

  @Test
  void badKeyIsReportedClearly() {
    JevRequest bad =
        new JevRequest(
            "sk-definitely-not-a-real-key",
            "anything",
            "Which team should handle this?",
            TestSupport.OPTIONS,
            0.7,
            null);

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(() -> new JevClient().call(bad))
                .getMessage())
        .contains("Jev API returned HTTP");
  }
}
