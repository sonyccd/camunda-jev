package ai.typesafe.camunda.jev;

import static ai.typesafe.camunda.jev.TestSupport.API_KEY;
import static ai.typesafe.camunda.jev.TestSupport.anyHandler;
import static ai.typesafe.camunda.jev.TestSupport.OPTIONS;
import static ai.typesafe.camunda.jev.TestSupport.httpResponse;
import static ai.typesafe.camunda.jev.TestSupport.jevResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.runtime.test.outbound.OutboundConnectorContextBuilder;
import io.camunda.connector.validation.impl.DefaultValidationProvider;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Threshold behaviour and response handling, with HTTP fully mocked. */
@ExtendWith(MockitoExtension.class)
class JevRouterFunctionTest {

  @Mock private HttpClient httpClient;
  private JevRouterFunction function;

  @BeforeEach
  void setUp() {
    function = new JevRouterFunction(new JevClient(httpClient, JevClient.defaultMapper()));
  }

  private JevResult route(double threshold, HttpResponse<String> response) throws Exception {
    when(httpClient.send(any(), anyHandler())).thenReturn(response);
    return (JevResult) function.execute(context(Map.of("threshold", threshold)));
  }

  static OutboundConnectorContextBuilder.TestConnectorContext context(
      Map<String, Object> overrides) {
    Map<String, Object> variables = new HashMap<>();
    variables.put("apiKey", API_KEY);
    variables.put("state", "Payouts have been failing for 3 days.");
    variables.put("question", "Which team should handle this?");
    variables.put("options", OPTIONS);
    variables.put("threshold", 0.7);
    variables.putAll(overrides);
    return OutboundConnectorContextBuilder.create()
        .variables(variables)
        .validation(new DefaultValidationProvider())
        .build();
  }

  @Test
  void confidenceAboveThreshold_returnsJevsPick() throws Exception {
    JevResult result = route(0.7, httpResponse(200, jevResponse("technical", 0.82)));

    assertThat(result.choice()).isEqualTo("technical");
    assertThat(result.decided()).isTrue();
    assertThat(result.jevChoice()).isEqualTo("technical");
    assertThat(result.confidence()).isEqualTo(0.82);
    assertThat(result.model()).isEqualTo("jev-1.13.0");
    assertThat(result.probabilities()).containsEntry("technical", 0.85);
  }

  @Test
  void confidenceExactlyAtThreshold_returnsJevsPick() throws Exception {
    // The boundary is >=, so an exact match must decide rather than abstain.
    JevResult result = route(0.7, httpResponse(200, jevResponse("billing", 0.7)));

    assertThat(result.choice()).isEqualTo("billing");
    assertThat(result.decided()).isTrue();
  }

  @Test
  void confidenceBelowThreshold_returnsUndecidedButKeepsJevsPick() throws Exception {
    JevResult result = route(0.9, httpResponse(200, jevResponse("technical", 0.82)));

    assertThat(result.choice()).isEqualTo("undecided");
    assertThat(result.decided()).isFalse();
    // Retained for debugging and threshold tuning.
    assertThat(result.jevChoice()).isEqualTo("technical");
    assertThat(result.confidence()).isEqualTo(0.82);
  }

  @Test
  void apiFailureThrowsRatherThanReturningUndecided() throws Exception {
    // "Broken" must stay distinguishable from "not confident".
    when(httpClient.send(any(), anyHandler()))
        .thenThrow(new IOException("connection reset"));

    assertThatThrownBy(() -> function.execute(context(Map.of())))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("Could not reach the Jev API");
  }

  @Test
  void choiceOutsideSuppliedOptionsThrows() throws Exception {
    assertThatThrownBy(() -> route(0.5, httpResponse(200, jevResponse("legal", 0.95))))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("not one of the supplied options");
  }

  @Test
  void missingAnswerForRouteKeyThrows() throws Exception {
    String noRouteKey = """
        {"model":"jev-1.13.0","answers":{"other":{"type":"choice","choice":"billing","confidence":0.9}}}
        """;

    assertThatThrownBy(() -> route(0.5, httpResponse(200, noRouteKey)))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("no answer under the key 'route'");
  }

  @Test
  void answerMissingConfidenceThrows() throws Exception {
    String noConfidence = """
        {"model":"jev-1.13.0","answers":{"route":{"type":"choice","choice":"billing"}}}
        """;

    assertThatThrownBy(() -> route(0.5, httpResponse(200, noConfidence)))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("missing 'choice' or 'confidence'");
  }
}
