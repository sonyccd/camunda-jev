package ai.typesafe.camunda.jev;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.mockito.Mockito;

/** Shared fixtures: canned Jev payloads, a fake HTTP response, and request-body capture. */
final class TestSupport {

  static final String API_KEY = "sk-test-key";
  static final Map<String, Object> OPTIONS =
      Map.of(
          "billing", "Payments, invoicing, refunds",
          "technical", "Bugs, outages, integrations",
          "sales", "Pricing, upgrades, new accounts");

  private TestSupport() {}

  /**
   * Typed matcher for the body handler argument. A bare {@code any()} makes javac infer
   * {@code HttpResponse<Object>}, which then will not accept an {@code HttpResponse<String>}.
   */
  static HttpResponse.BodyHandler<String> anyHandler() {
    return org.mockito.ArgumentMatchers.any();
  }

  /** A well-formed Choice response, matching the shape documented at docs.typesafe.ai. */
  static String jevResponse(String choice, double confidence) {
    return """
        {
          "model": "jev-1.13.0",
          "answers": {
            "route": {
              "type": "choice",
              "choice": "%s",
              "probabilities": { "billing": 0.08, "technical": 0.85, "sales": 0.07 },
              "confidence": %s
            }
          },
          "usage": { "input_tokens": 312, "output_tokens": 48 }
        }
        """
        .formatted(choice, confidence);
  }

  static JevRequest request(Object state, Map<String, Object> options, Double threshold) {
    return new JevRequest(API_KEY, state, "Which team should handle this?", options, threshold, null);
  }

  @SuppressWarnings("unchecked")
  static HttpResponse<String> httpResponse(int status, String body) {
    HttpResponse<String> response = Mockito.mock(HttpResponse.class);
    Mockito.lenient().when(response.statusCode()).thenReturn(status);
    Mockito.lenient().when(response.body()).thenReturn(body);
    Mockito.lenient().when(response.headers())
        .thenReturn(
            HttpHeaders.of(
                Map.of("x-typesafe-request-id", List.of("req_test123")), (k, v) -> true));
    return response;
  }

  /** Drains a request's BodyPublisher so tests can assert on the JSON actually sent. */
  static String bodyOf(HttpRequest request) {
    StringBuilder body = new StringBuilder();
    CountDownLatch done = new CountDownLatch(1);
    request
        .bodyPublisher()
        .orElseThrow()
        .subscribe(
            new Flow.Subscriber<ByteBuffer>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(ByteBuffer item) {
                body.append(StandardCharsets.UTF_8.decode(item));
              }

              @Override
              public void onError(Throwable throwable) {
                done.countDown();
              }

              @Override
              public void onComplete() {
                done.countDown();
              }
            });
    try {
      done.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    return body.toString();
  }
}
