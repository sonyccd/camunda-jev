package ai.typesafe.camunda.jev;

import io.camunda.connector.api.annotation.OutboundConnector;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.api.outbound.OutboundConnectorFunction;
import io.camunda.connector.generator.java.annotation.ElementTemplate;
import io.camunda.connector.generator.java.annotation.ElementTemplate.PropertyGroup;
import java.util.Collection;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes work with a single Jev Choice judgment.
 *
 * <p>Returns {@code undecided} when Jev's confidence falls below the configured threshold, so that
 * uncertain work can be sent to a human instead of guessed at. API failures throw instead, and
 * become Camunda incidents — "not confident" and "broken" are deliberately kept apart.
 */
@OutboundConnector(
    name = "Jev Router",
    type = JevRouterFunction.TYPE,
    inputVariables = {"apiKey", "state", "question", "options", "threshold", "model"})
@ElementTemplate(
    id = "ai.typesafe.camunda.jev.router.v1",
    name = "Jev Router",
    version = 1,
    inputDataClass = JevRequest.class,
    description = "Route work by meaning using a TypeSafe Jev Choice judgment.",
    documentationRef = "https://docs.typesafe.ai/primitives/choice",
    defaultResultVariable = "jevResult",
    propertyGroups = {
      @PropertyGroup(id = "authentication", label = "Authentication"),
      @PropertyGroup(id = "input", label = "Routing decision"),
      @PropertyGroup(id = "advanced", label = "Advanced", openByDefault = false)
    })
public class JevRouterFunction implements OutboundConnectorFunction {

  public static final String TYPE = "ai.typesafe:jev-router:1";

  /**
   * Returned when confidence is below the threshold. Never sent to the API as an option, so it
   * costs none of the 255 option slots and never competes for probability mass.
   */
  public static final String UNDECIDED = "undecided";

  static final int MIN_OPTIONS = 2;
  static final int MAX_OPTIONS = 255;

  private static final Logger LOG = LoggerFactory.getLogger(JevRouterFunction.class);

  private final JevClient client;

  public JevRouterFunction() {
    this(new JevClient());
  }

  /** Test seam. */
  JevRouterFunction(JevClient client) {
    this.client = client;
  }

  @Override
  public Object execute(OutboundConnectorContext context) {
    JevRequest request = context.bindVariables(JevRequest.class);
    validate(request);

    LOG.debug(
        "Routing with Jev: model={}, options={}, threshold={}, stateType={}, stateLength={}",
        JevClient.modelOf(request),
        request.options().size(),
        request.threshold(),
        request.state().getClass().getSimpleName(),
        stateLength(request.state()));

    JevResponse response = client.call(request);
    JevResponse.Answer answer = response.answers().get(JevClient.QUESTION_KEY);

    if (answer == null) {
      throw new ConnectorException(
          "JEV_MALFORMED_RESPONSE",
          "Jev response contained no answer under the key '" + JevClient.QUESTION_KEY + "'");
    }
    if (answer.choice() == null || answer.confidence() == null) {
      throw new ConnectorException(
          "JEV_MALFORMED_RESPONSE", "Jev answer was missing 'choice' or 'confidence'");
    }
    // The API guarantees answers are constrained to the supplied options, so this means the
    // contract broke. Surface it loudly rather than disguising it as a business outcome.
    if (!request.options().containsKey(answer.choice())) {
      throw new ConnectorException(
          "JEV_MALFORMED_RESPONSE",
          "Jev returned '" + answer.choice() + "', which is not one of the supplied options");
    }

    boolean decided = answer.confidence() >= request.threshold();
    return new JevResult(
        decided ? answer.choice() : UNDECIDED,
        decided,
        answer.confidence(),
        answer.choice(),
        answer.probabilities(),
        response.model());
  }

  /**
   * Validates the rules that carry their own messages. Presence is additionally enforced by the
   * bean-validation annotations on {@link JevRequest}, which also drive the element template's
   * required-field constraints.
   */
  private static void validate(JevRequest request) {
    if (isEmptyState(request.state())) {
      throw new ConnectorException("INVALID_INPUT", "State must not be empty.");
    }

    Map<String, Object> options = request.options();
    if (options == null || options.size() < MIN_OPTIONS) {
      throw new ConnectorException(
          "INVALID_INPUT",
          "At least "
              + MIN_OPTIONS
              + " options are required, but got "
              + (options == null ? 0 : options.size())
              + ".");
    }
    if (options.size() > MAX_OPTIONS) {
      throw new ConnectorException(
          "INVALID_INPUT",
          "At most " + MAX_OPTIONS + " options are allowed, but got " + options.size() + ".");
    }
    for (String name : options.keySet()) {
      if (UNDECIDED.equalsIgnoreCase(name)) {
        throw new ConnectorException(
            "INVALID_INPUT",
            "'" + UNDECIDED + "' is reserved and cannot be used as an option name.");
      }
    }

    Double threshold = request.threshold();
    if (threshold == null || threshold.isNaN() || threshold < 0.0 || threshold > 1.0) {
      throw new ConnectorException(
          "INVALID_INPUT", "Confidence threshold must be between 0 and 1, but was " + threshold + ".");
    }
  }

  private static boolean isEmptyState(Object state) {
    return switch (state) {
      case null -> true;
      case CharSequence s -> s.toString().isBlank();
      case Map<?, ?> m -> m.isEmpty();
      case Collection<?> c -> c.isEmpty();
      default -> false;
    };
  }

  /** Length only — state content is never logged. */
  private static Object stateLength(Object state) {
    return switch (state) {
      case CharSequence s -> s.length();
      case Map<?, ?> m -> m.size();
      case Collection<?> c -> c.size();
      default -> "n/a";
    };
  }
}
