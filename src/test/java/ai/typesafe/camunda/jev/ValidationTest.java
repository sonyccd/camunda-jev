package ai.typesafe.camunda.jev;

import static ai.typesafe.camunda.jev.JevRouterFunctionTest.context;
import static ai.typesafe.camunda.jev.TestSupport.anyHandler;
import static ai.typesafe.camunda.jev.TestSupport.httpResponse;
import static ai.typesafe.camunda.jev.TestSupport.jevResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.camunda.connector.api.error.ConnectorException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Input validation. Every case must fail before any HTTP call is made — the mocked client is left
 * unstubbed, so any attempt to call out would fail the test.
 */
@ExtendWith(MockitoExtension.class)
class ValidationTest {

  @Mock private HttpClient httpClient;

  private void expectRejection(Map<String, Object> overrides, String expectedMessage) {
    JevRouterFunction function =
        new JevRouterFunction(new JevClient(httpClient, JevClient.defaultMapper()));

    assertThatThrownBy(() -> function.execute(context(overrides)))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining(expectedMessage);
  }

  private static Map<String, Object> options(int count) {
    Map<String, Object> options = new LinkedHashMap<>();
    for (int i = 0; i < count; i++) {
      options.put("option" + i, "Description " + i);
    }
    return options;
  }

  @Test
  void singleOptionIsRejected() {
    expectRejection(Map.of("options", options(1)), "At least 2 options are required");
  }

  @Test
  void twoHundredFiftySixOptionsAreRejected() {
    expectRejection(Map.of("options", options(256)), "At most 255 options are allowed");
  }

  @Test
  void twoHundredFiftyFiveOptionsAreAccepted() throws Exception {
    // The documented ceiling is inclusive, so 255 must pass validation and route normally.
    HttpResponse<String> response = httpResponse(200, jevResponse("option0", 0.95));
    when(httpClient.send(any(), anyHandler())).thenReturn(response);
    JevRouterFunction function =
        new JevRouterFunction(new JevClient(httpClient, JevClient.defaultMapper()));

    JevResult result = (JevResult) function.execute(context(Map.of("options", options(255))));

    assertThat(result.choice()).isEqualTo("option0");
    assertThat(result.decided()).isTrue();
  }

  @Test
  void optionNamedUndecidedIsRejected() {
    Map<String, Object> reserved = new LinkedHashMap<>();
    reserved.put("billing", "Payments");
    reserved.put("undecided", "Not sure");

    expectRejection(Map.of("options", reserved), "'undecided' is reserved");
  }

  @Test
  void optionNamedUndecidedIsRejectedRegardlessOfCase() {
    Map<String, Object> reserved = new LinkedHashMap<>();
    reserved.put("billing", "Payments");
    reserved.put("UNDECIDED", "Not sure");

    expectRejection(Map.of("options", reserved), "'undecided' is reserved");
  }

  @Test
  void thresholdAboveOneIsRejected() {
    expectRejection(Map.of("threshold", 1.5), "must be between 0 and 1");
  }

  @Test
  void negativeThresholdIsRejected() {
    expectRejection(Map.of("threshold", -0.1), "must be between 0 and 1");
  }

  @Test
  void thresholdBoundsAreInclusive() throws Exception {
    // 0.0 and 1.0 are both valid thresholds, so both must route rather than be rejected.
    HttpResponse<String> response = httpResponse(200, jevResponse("technical", 0.9));
    when(httpClient.send(any(), anyHandler())).thenReturn(response);
    JevRouterFunction function =
        new JevRouterFunction(new JevClient(httpClient, JevClient.defaultMapper()));

    // Threshold 0.0 accepts anything Jev picks.
    JevResult atZero = (JevResult) function.execute(context(Map.of("threshold", 0.0)));
    assertThat(atZero.choice()).isEqualTo("technical");
    assertThat(atZero.decided()).isTrue();

    // Threshold 1.0 is valid but effectively unreachable, so 0.9 falls short.
    JevResult atOne = (JevResult) function.execute(context(Map.of("threshold", 1.0)));
    assertThat(atOne.choice()).isEqualTo("undecided");
    assertThat(atOne.decided()).isFalse();
    assertThat(atOne.jevChoice()).isEqualTo("technical");
  }

  @Test
  void emptyStateIsRejected() {
    expectRejection(Map.of("state", "   "), "State must not be empty");
  }

  @Test
  void emptyObjectStateIsRejected() {
    expectRejection(Map.of("state", Map.of()), "State must not be empty");
  }
}
