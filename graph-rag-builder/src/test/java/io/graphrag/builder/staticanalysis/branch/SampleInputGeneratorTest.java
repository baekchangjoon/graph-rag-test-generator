package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.builder.staticanalysis.domain.MethodAnalysis;
import io.graphrag.builder.staticanalysis.domain.Parameter;
import io.graphrag.builder.staticanalysis.domain.ReturnType;
import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.SampleInput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SampleInputGeneratorTest {

    private final BoundaryValueConfig cfg = BoundaryValueConfig.defaults();
    private final List<ManualReviewItem> queue = new ArrayList<>();
    private final ManualReviewSink sink = ManualReviewSink.collectingInto(queue);

    private static Endpoint ep(HttpMethod m, String path, String klass, String mname) {
        return new Endpoint(m.name() + ":" + path, m, path, "petclinic",
                klass, mname, false, List.of());
    }

    private static MethodAnalysis ma(String klass, String mname, List<Parameter> params) {
        return new MethodAnalysis(klass, mname, params,
                List.of(), List.of(), ReturnType.of("void"));
    }

    @Test
    void endpoint_with_no_params_emits_only_happy() {
        Endpoint endpoint = ep(HttpMethod.GET, "/vets", "com.x.VetController", "list");
        MethodAnalysis methodAnalysis = ma("com.x.VetController", "list", List.of());

        List<NamedSampleInput> inputs =
                SampleInputGenerator.generate(endpoint, methodAnalysis, cfg, sink);

        assertThat(inputs).hasSize(1);
        assertThat(inputs.get(0).slug()).isEqualTo("happy");
        assertThat(inputs.get(0).predictedStatus()).isEqualTo(200);
        assertThat(inputs.get(0).input().pathParams()).isEmpty();
        assertThat(inputs.get(0).input().queryParams()).isEmpty();
        assertThat(inputs.get(0).input().body()).isNull();
        assertThat(queue).isEmpty();
    }

    @Test
    void single_numeric_pathvar_emits_happy_plus_three_variants() {
        Endpoint endpoint = ep(HttpMethod.GET, "/owners/{id}", "com.x.OwnerController", "get");
        Parameter id = new Parameter("id", "Integer", List.of("PathVariable"));
        MethodAnalysis methodAnalysis = ma("com.x.OwnerController", "get", List.of(id));

        List<NamedSampleInput> inputs =
                SampleInputGenerator.generate(endpoint, methodAnalysis, cfg, sink);

        assertThat(inputs).hasSize(4);
        assertThat(inputs.get(0).slug()).isEqualTo("happy");
        assertThat(inputs.get(0).input().pathParams()).containsExactly(Map.entry("id", "1"));
        assertThat(inputs.get(0).predictedStatus()).isEqualTo(200);

        assertThat(inputs.get(1).slug()).isEqualTo("id-neg1");
        assertThat(inputs.get(1).input().pathParams()).containsExactly(Map.entry("id", "-1"));
        assertThat(inputs.get(1).predictedStatus()).isEqualTo(404);

        assertThat(inputs.get(2).slug()).isEqualTo("id-0");
        assertThat(inputs.get(2).input().pathParams()).containsExactly(Map.entry("id", "0"));

        assertThat(inputs.get(3).slug()).isEqualTo("id-" + Integer.MAX_VALUE);
        assertThat(inputs.get(3).input().pathParams())
                .containsExactly(Map.entry("id", String.valueOf(Integer.MAX_VALUE)));
    }

    @Test
    void single_string_querystring_emits_happy_plus_empty() {
        Endpoint endpoint = ep(HttpMethod.GET, "/owners", "com.x.OwnerController", "search");
        Parameter q = new Parameter("q", "String", List.of("RequestParam"));
        MethodAnalysis methodAnalysis = ma("com.x.OwnerController", "search", List.of(q));

        List<NamedSampleInput> inputs =
                SampleInputGenerator.generate(endpoint, methodAnalysis, cfg, sink);

        assertThat(inputs).hasSize(2);
        assertThat(inputs.get(0).input().queryParams()).containsExactly(Map.entry("q", "a"));
        assertThat(inputs.get(1).slug()).isEqualTo("q-empty");
        assertThat(inputs.get(1).input().queryParams()).containsExactly(Map.entry("q", ""));
        assertThat(inputs.get(1).predictedStatus()).isEqualTo(400);
    }

    @Test
    void request_body_endpoint_gets_empty_object_body() {
        Endpoint endpoint = ep(HttpMethod.POST, "/owners", "com.x.OwnerController", "create");
        Parameter body = new Parameter("body", "Owner", List.of("RequestBody"));
        MethodAnalysis methodAnalysis = ma("com.x.OwnerController", "create", List.of(body));

        List<NamedSampleInput> inputs =
                SampleInputGenerator.generate(endpoint, methodAnalysis, cfg, sink);

        assertThat(inputs).hasSize(1);
        SampleInput happy = inputs.get(0).input();
        assertThat(happy.body()).isInstanceOf(Map.class);
        assertThat((Map<?, ?>) happy.body()).isEmpty();
        assertThat(inputs.get(0).predictedStatus()).isEqualTo(201);
        // Body params get a complex_parameter_type queue entry because Owner is not numeric/string.
        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).kind()).isEqualTo("complex_parameter_type");
    }

    @Test
    void multi_param_only_varies_one_at_a_time() {
        Endpoint endpoint = ep(HttpMethod.GET, "/owners/{id}/pets/{petId}",
                "com.x.OwnerController", "getPet");
        Parameter id = new Parameter("id", "Integer", List.of("PathVariable"));
        Parameter petId = new Parameter("petId", "Integer", List.of("PathVariable"));
        MethodAnalysis methodAnalysis = ma("com.x.OwnerController", "getPet", List.of(id, petId));

        List<NamedSampleInput> inputs =
                SampleInputGenerator.generate(endpoint, methodAnalysis, cfg, sink);

        // 1 happy + 3 variants for id + 3 variants for petId = 7
        assertThat(inputs).hasSize(7);
        assertThat(inputs.get(0).slug()).isEqualTo("happy");

        // When varying id, petId stays "1".
        NamedSampleInput idVariant = inputs.get(1);
        assertThat(idVariant.slug()).isEqualTo("id-neg1");
        assertThat(idVariant.input().pathParams())
                .containsExactly(Map.entry("id", "-1"), Map.entry("petId", "1"));

        // When varying petId, id stays "1".
        NamedSampleInput petIdVariant = inputs.get(4);
        assertThat(petIdVariant.slug()).isEqualTo("petId-neg1");
        assertThat(petIdVariant.input().pathParams())
                .containsExactly(Map.entry("id", "1"), Map.entry("petId", "-1"));
    }

    @Test
    void deterministic_order_under_repeat_invocation() {
        Endpoint endpoint = ep(HttpMethod.GET, "/owners/{id}", "com.x.OwnerController", "get");
        Parameter id = new Parameter("id", "Integer", List.of("PathVariable"));
        MethodAnalysis methodAnalysis = ma("com.x.OwnerController", "get", List.of(id));

        List<NamedSampleInput> r1 =
                SampleInputGenerator.generate(endpoint, methodAnalysis, cfg, ManualReviewSink.discarding());
        List<NamedSampleInput> r2 =
                SampleInputGenerator.generate(endpoint, methodAnalysis, cfg, ManualReviewSink.discarding());

        assertThat(r1).isEqualTo(r2);
    }
}
