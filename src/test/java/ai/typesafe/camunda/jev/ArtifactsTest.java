package ai.typesafe.camunda.jev;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
}
