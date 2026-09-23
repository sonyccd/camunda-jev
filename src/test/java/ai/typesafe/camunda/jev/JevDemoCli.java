package ai.typesafe.camunda.jev;

import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.runtime.test.outbound.OutboundConnectorContextBuilder;
import io.camunda.connector.validation.impl.DefaultValidationProvider;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Routes one piece of text from the command line, for {@code ./jev ask "..."}.
 *
 * <p>Deliberately goes through {@link JevRouterFunction} with a real context rather than calling
 * the API directly, so what you see here is the same code path the Connector Runtime executes.
 */
public final class JevDemoCli {

  private static final Map<String, Object> DEFAULT_OPTIONS = new LinkedHashMap<>();

  static {
    DEFAULT_OPTIONS.put("billing", "Payments, invoicing, refunds");
    DEFAULT_OPTIONS.put("technical", "Bugs, outages, integrations");
    DEFAULT_OPTIONS.put("sales", "Pricing, upgrades, new accounts");
  }

  public static void main(String[] args) {
    String apiKey = System.getenv("TYPESAFE_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      System.err.println("TYPESAFE_API_KEY is not set.");
      System.exit(2);
    }

    String text = String.join(" ", args).trim();
    if (text.isEmpty()) {
      System.err.println("usage: ./jev ask \"my payouts have been failing\"");
      System.exit(2);
    }

    double threshold = Double.parseDouble(System.getenv().getOrDefault("JEV_THRESHOLD", "0.7"));

    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("apiKey", apiKey);
    variables.put("state", text);
    variables.put("question", "Which team should handle this?");
    variables.put("options", DEFAULT_OPTIONS);
    variables.put("threshold", threshold);

    var context =
        OutboundConnectorContextBuilder.create()
            .variables(variables)
            .validation(new DefaultValidationProvider())
            .build();

    try {
      JevResult result = (JevResult) new JevRouterFunction().execute(context);
      print(result, threshold);
    } catch (ConnectorException e) {
      // A demo tool should explain the failure, not bury it in a stack trace.
      System.out.println();
      System.out.println("  could not route: " + e.getMessage());
      if (e.getMessage() != null
          && (e.getMessage().contains("HTTP 401") || e.getMessage().contains("HTTP 403"))) {
        System.out.println();
        System.out.println("  That is an authentication failure. Check TYPESAFE_API_KEY.");
      }
      System.out.println();
      System.exit(1);
    }
  }

  private static void print(JevResult result, double threshold) {
    System.out.println();
    System.out.printf("  choice       %s%n", result.choice());
    System.out.printf(
        "  decided      %s%s%n",
        result.decided(),
        result.decided() ? "" : "   (below threshold " + threshold + " -> human triage)");
    System.out.printf("  confidence   %.3f%n", result.confidence());
    System.out.printf("  jev picked   %s%n", result.jevChoice());
    System.out.printf("  model        %s%n", result.model());

    if (result.probabilities() != null && !result.probabilities().isEmpty()) {
      System.out.println("  probabilities");
      result.probabilities().entrySet().stream()
          .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
          .forEach(
              e ->
                  System.out.printf(
                      "    %-12s %5.2f  %s%n",
                      e.getKey(),
                      e.getValue(),
                      "#".repeat(Math.max(1, (int) Math.round(e.getValue() * 30)))));
    }
    System.out.println();
  }

  private JevDemoCli() {}
}
