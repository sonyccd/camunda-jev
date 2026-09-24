package ai.typesafe.camunda.jev;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Guards the two shipped artifacts against drift: the generated element template and the example
 * process. The template assertions are skipped until {@code mvn package} has generated it, so a
 * bare {@code mvn test} on a clean checkout still runs.
 */
class ArtifactsTest {

  private static final Path TEMPLATE = Path.of("element-templates/jev-router-connector.json");
  private static final Path BPMN = Path.of("examples/support-ticket-routing.bpmn");
  private static final Path LOADTEST = Path.of("examples/support-ticket-loadtest.bpmn");

  static boolean templateMissing() {
    return !Files.exists(TEMPLATE);
  }

  @Test
  @DisabledIf("templateMissing")
  void elementTemplateExposesEveryModelerField() throws Exception {
    JsonNode template = new ObjectMapper().readTree(Files.readString(TEMPLATE));

    assertThat(template.get("id").asText()).isEqualTo("ai.typesafe.camunda.jev.router.v1");
    assertThat(template.get("version").asInt()).isEqualTo(1);

    Set<String> ids = new HashSet<>();
    for (JsonNode property : template.get("properties")) {
      if (property.hasNonNull("id")) {
        ids.add(property.get("id").asText());
      }
    }
    assertThat(ids)
        .contains("apiKey", "state", "question", "options", "threshold", "model", "resultVariable");
  }

  @Test
  @DisabledIf("templateMissing")
  void elementTemplateCarriesTheDocumentedDefaults() throws Exception {
    JsonNode template = new ObjectMapper().readTree(Files.readString(TEMPLATE));

    assertThat(valueOf(template, "apiKey")).isEqualTo("{{secrets.TYPESAFE_API_KEY}}");
    assertThat(valueOf(template, "threshold")).isEqualTo("0.7");
    assertThat(valueOf(template, "model")).isEqualTo(JevClient.DEFAULT_MODEL);
    assertThat(valueOf(template, "resultVariable")).isEqualTo("jevResult");

    // Model belongs in a collapsed group, so it stays out of the way of routine editing.
    assertThat(groupOf(template, "model")).isEqualTo("advanced");
    for (JsonNode group : template.get("groups")) {
      if ("advanced".equals(group.get("id").asText())) {
        assertThat(group.get("openByDefault").asBoolean()).isFalse();
      }
    }
  }

  private static String valueOf(JsonNode template, String id) {
    for (JsonNode property : template.get("properties")) {
      if (id.equals(property.path("id").asText())) {
        return property.path("value").asText();
      }
    }
    throw new AssertionError("no property with id " + id);
  }

  private static String groupOf(JsonNode template, String id) {
    for (JsonNode property : template.get("properties")) {
      if (id.equals(property.path("id").asText())) {
        return property.path("group").asText();
      }
    }
    throw new AssertionError("no property with id " + id);
  }

  @Test
  void exampleProcessBranchesOnEveryOptionPlusUndecided() throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var document = factory.newDocumentBuilder().parse(BPMN.toFile());

    NodeList expressions = document.getElementsByTagNameNS("*", "conditionExpression");
    List<String> conditions = new ArrayList<>();
    for (int i = 0; i < expressions.getLength(); i++) {
      conditions.add(expressions.item(i).getTextContent().trim());
    }

    assertThat(conditions)
        .containsExactlyInAnyOrder(
            "=jevResult.choice = \"billing\"",
            "=jevResult.choice = \"technical\"",
            "=jevResult.choice = \"sales\"",
            "=jevResult.choice = \"undecided\"");
  }

  @Test
  void exampleProcessCallsThisConnector() throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var document = factory.newDocumentBuilder().parse(BPMN.toFile());

    NodeList definitions = document.getElementsByTagNameNS("*", "taskDefinition");
    assertThat(definitions.getLength()).isEqualTo(1);
    assertThat(((Element) definitions.item(0)).getAttribute("type"))
        .isEqualTo(JevRouterFunction.TYPE);

    // The undecided branch must land on a human, which is the whole point of the fallback.
    NodeList userTasks = document.getElementsByTagNameNS("*", "userTask");
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < userTasks.getLength(); i++) {
      ids.add(((Element) userTasks.item(i)).getAttribute("id"));
    }
    assertThat(ids).contains("Task_HumanTriage");
  }

  @Test
  void loadTestProcessBranchesOnEveryOptionPlusUndecided() throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var document = factory.newDocumentBuilder().parse(LOADTEST.toFile());

    NodeList expressions = document.getElementsByTagNameNS("*", "conditionExpression");
    List<String> conditions = new ArrayList<>();
    for (int i = 0; i < expressions.getLength(); i++) {
      conditions.add(expressions.item(i).getTextContent().trim());
    }

    assertThat(conditions)
        .containsExactlyInAnyOrder(
            "=jevResult.choice = \"billing\"",
            "=jevResult.choice = \"technical\"",
            "=jevResult.choice = \"sales\"",
            "=jevResult.choice = \"undecided\"");
  }

  @Test
  void loadTestProcessAutoClosesConfidentRoutesAndParksEscalations() throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var document = factory.newDocumentBuilder().parse(LOADTEST.toFile());

    // Exactly one user task: the human queue. Confident routes must not park,
    // or throughput would measure nothing but an unattended queue.
    NodeList userTasks =
        document.getElementsByTagNameNS(
            "http://www.omg.org/spec/BPMN/20100524/MODEL", "userTask");
    assertThat(userTasks.getLength()).isEqualTo(1);
    assertThat(((Element) userTasks.item(0)).getAttribute("id")).isEqualTo("Task_HumanReview");

    NodeList definitions = document.getElementsByTagNameNS("*", "taskDefinition");
    assertThat(definitions.getLength()).isEqualTo(1);
    assertThat(((Element) definitions.item(0)).getAttribute("type"))
        .isEqualTo(JevRouterFunction.TYPE);
  }

  @Test
  void loadTestProcessNeverSendsGroundTruthToJev() throws Exception {
    // `expected` is the label the report scores against. If it reached the
    // model, every accuracy number would be invalid but still look plausible.
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var document = factory.newDocumentBuilder().parse(LOADTEST.toFile());

    NodeList inputs = document.getElementsByTagNameNS("*", "input");
    assertThat(inputs.getLength()).isGreaterThan(0);
    for (int i = 0; i < inputs.getLength(); i++) {
      Element input = (Element) inputs.item(i);
      assertThat(input.getAttribute("source")).doesNotContain("expected");
      assertThat(input.getAttribute("target")).isNotEqualTo("expected");
    }
  }

  @Test
  void loadTestProcessTakesThresholdFromAVariable() throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var document = factory.newDocumentBuilder().parse(LOADTEST.toFile());

    NodeList inputs = document.getElementsByTagNameNS("*", "input");
    String thresholdSource = null;
    for (int i = 0; i < inputs.getLength(); i++) {
      Element input = (Element) inputs.item(i);
      if ("threshold".equals(input.getAttribute("target"))) {
        thresholdSource = input.getAttribute("source");
      }
    }
    // Set per run by the feeder, never hard-coded in the diagram.
    assertThat(thresholdSource).isEqualTo("=threshold");
  }

  @Test
  void loadTestProcessGatewayFlowsReachTheCorrectDestination() throws Exception {
    // Condition text and element counts alone don't prove the wiring is right: a
    // diagram with the right four condition strings but crossed targetRefs (e.g.
    // "billing" routed to End_Sales) would still pass the other two tests.
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var document = factory.newDocumentBuilder().parse(LOADTEST.toFile());

    NodeList flows =
        document.getElementsByTagNameNS(
            "http://www.omg.org/spec/BPMN/20100524/MODEL", "sequenceFlow");
    Map<String, String> conditionToTarget = new HashMap<>();
    Map<String, String> conditionToSource = new HashMap<>();
    for (int i = 0; i < flows.getLength(); i++) {
      Element flow = (Element) flows.item(i);
      NodeList expressions =
          flow.getElementsByTagNameNS(
              "http://www.omg.org/spec/BPMN/20100524/MODEL", "conditionExpression");
      if (expressions.getLength() == 0) {
        continue;
      }
      String condition = expressions.item(0).getTextContent().trim();
      conditionToTarget.put(condition, flow.getAttribute("targetRef"));
      conditionToSource.put(condition, flow.getAttribute("sourceRef"));
    }

    assertThat(conditionToTarget)
        .containsEntry("=jevResult.choice = \"billing\"", "End_Billing")
        .containsEntry("=jevResult.choice = \"technical\"", "End_Technical")
        .containsEntry("=jevResult.choice = \"sales\"", "End_Sales")
        .containsEntry("=jevResult.choice = \"undecided\"", "Task_HumanReview");

    // None of these conditional flows may be re-parented off the gateway.
    assertThat(conditionToSource.values()).allMatch("Gateway_Route"::equals);
  }
}
