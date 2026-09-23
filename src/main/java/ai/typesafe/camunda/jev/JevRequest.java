package ai.typesafe.camunda.jev;

import io.camunda.connector.generator.java.annotation.FeelMode;
import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.DefaultValueType;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * Inputs for one Jev routing decision. The annotations on this record are the source of truth for
 * the Modeler element template — {@code element-templates/jev-router-connector.json} is generated
 * from them at {@code mvn package} and must never be hand-edited.
 *
 * <p>{@code state} is typed {@link Object} and {@code options} values are typed {@link Object} on
 * purpose: the Jev API accepts a string, object or array for state, and a string, object, array or
 * null for each option's description. Narrowing either to {@code String} would block documented
 * usage for no benefit.
 */
public record JevRequest(
    @NotEmpty
        @TemplateProperty(
            group = "authentication",
            label = "API key",
            description = "TypeSafe API key. Use a Camunda secret rather than pasting a raw key.",
            type = PropertyType.String,
            feel = FeelMode.optional,
            defaultValue = "{{secrets.TYPESAFE_API_KEY}}")
        String apiKey,

    @NotNull
        @TemplateProperty(
            group = "input",
            label = "State",
            description =
                "The data Jev judges. A string, or a FEEL expression resolving to a map or list, "
                    + "which is sent as structured JSON.",
            type = PropertyType.Text,
            feel = FeelMode.optional)
        Object state,

    @NotEmpty
        @TemplateProperty(
            group = "input",
            label = "Question",
            description = "Plain-language instruction, e.g. \"Which team should handle this?\"",
            type = PropertyType.Text,
            feel = FeelMode.optional)
        String question,

    @NotEmpty
        @TemplateProperty(
            group = "input",
            label = "Options",
            description =
                "FEEL map of option name to description, e.g. "
                    + "{\"billing\": \"Payments, refunds\", \"technical\": \"Bugs, outages\"}. "
                    + "2 to 255 options. \"undecided\" is reserved.",
            type = PropertyType.Text,
            feel = FeelMode.required)
        Map<String, Object> options,

    @NotNull
        @TemplateProperty(
            group = "input",
            label = "Confidence threshold",
            description =
                "How sure should the AI be before it commits? Below this, the result is "
                    + "\"undecided\". Note confidence is not the winning option's probability.",
            type = PropertyType.Number,
            feel = FeelMode.optional,
            defaultValue = "0.7",
            defaultValueType = DefaultValueType.Number)
        Double threshold,

    @TemplateProperty(
            group = "advanced",
            label = "Model",
            description = "Jev model ID. Pin a version once you have tuned a threshold against it.",
            type = PropertyType.String,
            feel = FeelMode.optional,
            optional = true,
            defaultValue = JevClient.DEFAULT_MODEL)
        String model) {}
