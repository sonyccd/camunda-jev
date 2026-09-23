package ai.typesafe.camunda.jev;

import static ai.typesafe.camunda.jev.TestSupport.OPTIONS;
import static ai.typesafe.camunda.jev.TestSupport.anyHandler;
import static ai.typesafe.camunda.jev.TestSupport.bodyOf;
import static ai.typesafe.camunda.jev.TestSupport.httpResponse;
import static ai.typesafe.camunda.jev.TestSupport.jevResponse;
import static ai.typesafe.camunda.jev.TestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.connector.api.error.ConnectorException;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Wire-level behaviour: what gets sent, and how failures surface. */
@ExtendWith(MockitoExtension.class)
class JevClientTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private HttpClient httpClient;
  private JevClient client;

  @BeforeEach
  void setUp() {
    client = new JevClient(httpClient, JevClient.defaultMapper());
  }

  private JsonNode sentBody(Object state) throws Exception {
    HttpResponse<String> response = httpResponse(200, jevResponse("technical", 0.9));
    when(httpClient.send(any(), anyHandler())).thenReturn(response);
    client.call(request(state, OPTIONS, 0.7));

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(captor.capture(), anyHandler());
    return MAPPER.readTree(bodyOf(captor.getValue()));
  }

  @Test
  void stringStateIsPassedThroughAsAString() throws Exception {
    JsonNode body = sentBody("Payouts failing for 3 days.");

    assertThat(body.get("state").isTextual()).isTrue();
    assertThat(body.get("state").asText()).isEqualTo("Payouts failing for 3 days.");
  }

  @Test
  void objectStateIsSentAsNativeJson() throws Exception {
    // The API accepts structured state, so a FEEL map is sent as a real JSON object
    // rather than being flattened into a string.
    JsonNode body = sentBody(Map.of("subject", "Payout failure", "tier", "enterprise"));

    assertThat(body.get("state").isObject()).isTrue();
    assertThat(body.get("state").get("subject").asText()).isEqualTo("Payout failure");
    assertThat(body.get("state").get("tier").asText()).isEqualTo("enterprise");
  }

  @Test
  void listStateIsSentAsNativeJsonArray() throws Exception {
    JsonNode body = sentBody(List.of(Map.of("role", "customer", "text", "my payouts fail")));

    assertThat(body.get("state").isArray()).isTrue();
    assertThat(body.get("state").get(0).get("role").asText()).isEqualTo("customer");
  }

  @Test
  void requestUsesFixedRouteKeyAndChoiceType() throws Exception {
    JsonNode body = sentBody("anything");
    JsonNode question = body.get("questions").get("route");

    assertThat(question).isNotNull();
    assertThat(question.get("type").asText()).isEqualTo("choice");
    assertThat(question.get("instructions").asText()).isEqualTo("Which team should handle this?");
    assertThat(question.get("criteria").get("billing").asText())
        .isEqualTo("Payments, invoicing, refunds");
  }

  @Test
  void modelIsAlwaysSentBecauseTheApiRequiresIt() throws Exception {
    // There is no server-side default; the alias default is an SDK behaviour only.
    JsonNode body = sentBody("anything");
    assertThat(body.get("model").asText()).isEqualTo(JevClient.DEFAULT_MODEL);
  }

  @Test
  void nestedCriteriaObjectsArePassedThroughUnchanged() throws Exception {
    Map<String, Object> richOptions = new HashMap<>();
    richOptions.put(
        "billing", Map.of("what", "Payments and refunds", "examples", List.of("refund my order")));
    richOptions.put("technical", "Bugs, outages");
    // A null description is valid per the API: "use null when an option needs no extra detail".
    richOptions.put("sales", null);

    HttpResponse<String> response = httpResponse(200, jevResponse("technical", 0.9));
    when(httpClient.send(any(), anyHandler())).thenReturn(response);
    client.call(request("anything", richOptions, 0.7));

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(captor.capture(), anyHandler());
    JsonNode criteria = MAPPER.readTree(bodyOf(captor.getValue())).get("questions").get("route").get("criteria");

    assertThat(criteria.get("billing").isObject()).isTrue();
    assertThat(criteria.get("billing").get("examples").get(0).asText()).isEqualTo("refund my order");
    assertThat(criteria.get("technical").isTextual()).isTrue();
    assertThat(criteria.get("sales").isNull()).isTrue();
  }

  @Test
  void authorizationHeaderCarriesTheApiKey() throws Exception {
    HttpResponse<String> response = httpResponse(200, jevResponse("technical", 0.9));
    when(httpClient.send(any(), anyHandler())).thenReturn(response);
    client.call(request("anything", OPTIONS, 0.7));

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(captor.capture(), anyHandler());

    assertThat(captor.getValue().headers().firstValue("Authorization"))
        .contains("Bearer " + TestSupport.API_KEY);
    assertThat(captor.getValue().uri().toString()).isEqualTo(JevClient.ENDPOINT);
  }

  @Test
  void nonSuccessStatusThrowsWithParsedDetailAndRequestId() throws Exception {
    // Real body shape, captured from the live API.
    String error =
        """
        {"detail":{"error_type":"authentication_error","message":"Must supply an API key!"}}
        """;
    HttpResponse<String> response = httpResponse(403, error);
    when(httpClient.send(any(), anyHandler())).thenReturn(response);

    assertThatThrownBy(() -> client.call(request("anything", OPTIONS, 0.7)))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("HTTP 403")
        .hasMessageContaining("authentication_error")
        .hasMessageContaining("Must supply an API key!")
        .hasMessageContaining("req_test123");
  }

  @Test
  void unrecognisedErrorBodyFallsBackToRawText() throws Exception {
    HttpResponse<String> response = httpResponse(502, "<html>bad gateway</html>");
    when(httpClient.send(any(), anyHandler())).thenReturn(response);

    assertThatThrownBy(() -> client.call(request("anything", OPTIONS, 0.7)))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("HTTP 502")
        .hasMessageContaining("bad gateway");
  }

  @Test
  void timeoutThrows() throws Exception {
    when(httpClient.send(any(), anyHandler())).thenThrow(new HttpTimeoutException("request timed out"));

    assertThatThrownBy(() -> client.call(request("anything", OPTIONS, 0.7)))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("did not respond within 10s");
  }

  @Test
  void networkFailureThrows() throws Exception {
    when(httpClient.send(any(), anyHandler())).thenThrow(new IOException("connection reset"));

    assertThatThrownBy(() -> client.call(request("anything", OPTIONS, 0.7)))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("Could not reach the Jev API");
  }

  @Test
  void malformedJsonThrows() throws Exception {
    HttpResponse<String> response = httpResponse(200, "{not valid json");
    when(httpClient.send(any(), anyHandler())).thenReturn(response);

    assertThatThrownBy(() -> client.call(request("anything", OPTIONS, 0.7)))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("Could not parse the Jev response");
  }

  @Test
  void responseWithoutAnswersThrows() throws Exception {
    HttpResponse<String> response = httpResponse(200, "{\"model\":\"jev-1.13.0\"}");
    when(httpClient.send(any(), anyHandler())).thenReturn(response);

    assertThatThrownBy(() -> client.call(request("anything", OPTIONS, 0.7)))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("contained no answers");
  }
}
