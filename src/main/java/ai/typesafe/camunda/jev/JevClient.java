package ai.typesafe.camunda.jev;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.connector.api.error.ConnectorException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thin HTTP client for the single Jev System One call this connector makes. */
public class JevClient {

  public static final String DEFAULT_MODEL = "jev-1.13.0";
  public static final String ENDPOINT = "https://api.typesafe.ai/v1/systemone";

  /** Fixed question key. Not sent to the model; it only correlates question and answer. */
  public static final String QUESTION_KEY = "route";

  static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final String REQUEST_ID_HEADER = "x-typesafe-request-id";
  private static final int MAX_ERROR_BODY = 300;

  private final HttpClient httpClient;
  private final ObjectMapper mapper;

  public JevClient() {
    this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), defaultMapper());
  }

  /** Test seam: lets unit tests supply a mocked {@link HttpClient}. */
  JevClient(HttpClient httpClient, ObjectMapper mapper) {
    this.httpClient = httpClient;
    this.mapper = mapper;
  }

  static ObjectMapper defaultMapper() {
    return new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  /**
   * Issues the Choice request and returns the parsed response.
   *
   * <p>Every failure mode — network, timeout, non-2xx, unparseable body — throws a {@link
   * ConnectorException}. None of them is ever converted into an "undecided" result: a broken call
   * and a low-confidence answer are different signals and must stay distinguishable.
   */
  public JevResponse call(JevRequest request) {
    String body = serializeBody(request);
    HttpRequest httpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(ENDPOINT))
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer " + request.apiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

    HttpResponse<String> response;
    try {
      response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    } catch (HttpTimeoutException e) {
      throw new ConnectorException(
          "JEV_TIMEOUT", "Jev API did not respond within " + TIMEOUT.toSeconds() + "s", e);
    } catch (IOException e) {
      throw new ConnectorException(
          "JEV_CONNECTION_ERROR", "Could not reach the Jev API: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ConnectorException("JEV_CONNECTION_ERROR", "Interrupted calling the Jev API", e);
    }

    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new ConnectorException("JEV_API_ERROR", describeError(response));
    }

    try {
      JevResponse parsed = mapper.readValue(response.body(), JevResponse.class);
      if (parsed == null || parsed.answers() == null) {
        throw new ConnectorException(
            "JEV_MALFORMED_RESPONSE", "Jev response contained no answers" + requestIdSuffix(response));
      }
      return parsed;
    } catch (ConnectorException e) {
      throw e;
    } catch (Exception e) {
      throw new ConnectorException(
          "JEV_MALFORMED_RESPONSE",
          "Could not parse the Jev response" + requestIdSuffix(response) + ": " + e.getMessage(),
          e);
    }
  }

  private String serializeBody(JevRequest request) {
    Map<String, Object> question = new LinkedHashMap<>();
    question.put("type", "choice");
    question.put("instructions", request.question());
    // Option descriptions pass through untouched: strings, nested objects and nulls are all valid.
    question.put("criteria", request.options());

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", modelOf(request));
    // `state` is serialized by type: a String stays a JSON string, a map or list becomes
    // structured JSON. The API accepts all three.
    body.put("state", request.state());
    body.put("questions", Map.of(QUESTION_KEY, question));

    try {
      return mapper.writeValueAsString(body);
    } catch (Exception e) {
      throw new ConnectorException(
          "INVALID_INPUT", "Could not serialize the Jev request: " + e.getMessage(), e);
    }
  }

  /** The model is required by the API; there is no server-side default. */
  static String modelOf(JevRequest request) {
    String model = request.model();
    return (model == null || model.isBlank()) ? DEFAULT_MODEL : model.trim();
  }

  /**
   * Builds a useful message from an error response. The error body shape is undocumented, so
   * {@code detail.error_type} / {@code detail.message} are read best-effort and the raw body is
   * used as a fallback. The request id is always included — support needs it.
   */
  private String describeError(HttpResponse<String> response) {
    String body = response.body();
    String detail = null;
    try {
      JsonNode node = mapper.readTree(body).path("detail");
      String message = node.path("message").asText(null);
      if (message != null) {
        String type = node.path("error_type").asText(null);
        detail = (type == null ? "" : type + ": ") + message;
      }
    } catch (Exception ignored) {
      // Fall through to the raw body.
    }
    if (detail == null) {
      detail = truncate(body);
    }
    return "Jev API returned HTTP "
        + response.statusCode()
        + " ("
        + detail
        + ")"
        + requestIdSuffix(response);
  }

  private static String requestIdSuffix(HttpResponse<?> response) {
    return response
        .headers()
        .firstValue(REQUEST_ID_HEADER)
        .map(id -> " [request-id=" + id + "]")
        .orElse("");
  }

  private static String truncate(String body) {
    if (body == null || body.isBlank()) {
      return "empty response body";
    }
    return body.length() <= MAX_ERROR_BODY ? body : body.substring(0, MAX_ERROR_BODY) + "…";
  }
}
