package io.graphrag.orchestrator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-discovered defensive filter from the live petclinic E2E: paths whose endpoint
 * URL has un-substituted {name} placeholders (because the static analyzer's
 * SampleInputGenerator couldn't fill them — typically when @PathVariable("ownerId")
 * is bound to a parameter whose Java name diverges from the URL placeholder name)
 * must be dropped at Stage 2, not propagated to ScoutStepTranslator (which throws).
 */
class IterationRunnerPlaceholderFilterTest {

    @Test
    void extractPlaceholders_extractsAllNamedPlaceholders() {
        assertThat(IterationRunner.extractPlaceholders("/owners/{ownerId}/edit"))
                .containsExactly("ownerId");
        assertThat(IterationRunner.extractPlaceholders("/owners/{ownerId}/pets/{petId}"))
                .containsExactlyInAnyOrder("ownerId", "petId");
    }

    @Test
    void extractPlaceholders_emptyOnTemplateWithoutPlaceholders() {
        assertThat(IterationRunner.extractPlaceholders("/api/owners")).isEmpty();
        assertThat(IterationRunner.extractPlaceholders("/")).isEmpty();
    }

    @Test
    void extractPlaceholders_handlesAdjacentPlaceholders() {
        assertThat(IterationRunner.extractPlaceholders("/{a}/{b}"))
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void resolveEnvVars_expandsBracedAndBareNames() {
        String home = System.getProperty("user.home");
        assertThat(IterationRunner.resolveEnvVars("${HOME}/foo")).isEqualTo(home + "/foo");
        assertThat(IterationRunner.resolveEnvVars("$HOME/bar")).isEqualTo(home + "/bar");
        assertThat(IterationRunner.resolveEnvVars("prefix-${HOME}-suffix"))
                .isEqualTo("prefix-" + home + "-suffix");
    }

    @Test
    void resolveEnvVars_leavesUnknownVarsUntouched() {
        assertThat(IterationRunner.resolveEnvVars("${THIS_VAR_DOES_NOT_EXIST_12345}/foo"))
                .isEqualTo("${THIS_VAR_DOES_NOT_EXIST_12345}/foo");
    }

    @Test
    void resolveEnvVars_passesThroughStringWithoutPlaceholders() {
        assertThat(IterationRunner.resolveEnvVars("/absolute/path/with/no/vars"))
                .isEqualTo("/absolute/path/with/no/vars");
    }
}
