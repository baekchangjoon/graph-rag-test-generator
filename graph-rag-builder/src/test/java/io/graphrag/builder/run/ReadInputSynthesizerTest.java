package io.graphrag.builder.run;

import io.graphrag.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ReadInputSynthesizerTest {

    @Test
    void integerFkSeedsTypedValuesAndSkipsNullableFkParent() {
        // petclinic 시나리오: 모든 PK/FK가 INT, owner_id는 nullable FK
        Endpoint endpoint = new Endpoint("get-api-pets-petid", "GET", "/api/pets/{petId}",
                "x.C", "get", List.of(new EndpointParam("petId", "int", ParamKind.PATH)), true);
        TableSchema types = new TableSchema("types",
                List.of(new ColumnSchema("id", "INT", false, true),
                        new ColumnSchema("name", "VARCHAR", false, false)),
                List.of(), List.of());
        TableSchema owners = new TableSchema("owners",
                List.of(new ColumnSchema("id", "INT", false, true),
                        new ColumnSchema("last_name", "VARCHAR", false, false)),
                List.of(), List.of());
        TableSchema pets = new TableSchema("pets",
                List.of(new ColumnSchema("id", "INT", false, true),
                        new ColumnSchema("type_id", "INT", false, false),     // NOT NULL FK
                        new ColumnSchema("owner_id", "INT", true, false)),    // nullable FK
                List.of(new ForeignKey("type_id", "types", "id"),
                        new ForeignKey("owner_id", "owners", "id")),
                List.of());

        SynthesizedInput out = new ReadInputSynthesizer().synthesize(endpoint, List.of(pets, types, owners));

        List<String> seededTables = out.seeds().stream()
                .map(SynthesizedInput.SeedRow::table).toList();
        // NOT NULL FK 부모(types)는 시드, nullable FK 부모(owners)는 미시드, target(pets) 포함
        assertThat(seededTables).containsExactly("types", "pets");

        SynthesizedInput.SeedRow petsRow = out.seeds().get(1);
        int typeFkIdx = petsRow.columns().indexOf("type_id");
        // INT FK 값은 비충돌 정수(>=90001, 엔드포인트별)여야 한다 (varchar "probe-..." 가 아님)
        assertThat(petsRow.values().get(typeFkIdx)).isInstanceOf(Integer.class);
        assertThat((Integer) petsRow.values().get(typeFkIdx)).isGreaterThanOrEqualTo(90001);
        assertThat(petsRow.columns()).doesNotContain("owner_id");   // nullable FK 미시드
        // 자식 FK 값 == 부모 PK 값
        SynthesizedInput.SeedRow typesRow = out.seeds().get(0);
        assertThat(typesRow.values().get(typesRow.columns().indexOf("id")))
                .isEqualTo(petsRow.values().get(typeFkIdx));
    }

    @Test
    void fkColumnSeedsParentTableFirst() {
        Endpoint endpoint = new Endpoint("get-api-orders-id", "GET", "/api/orders/{id}",
                "x.C", "get", java.util.List.of(new EndpointParam("id", "java.lang.Long", ParamKind.PATH)),
                true);
        TableSchema users = new TableSchema("users",
                java.util.List.of(new ColumnSchema("id", "VARCHAR", false, true),
                        new ColumnSchema("name", "VARCHAR", false, false)),
                java.util.List.of(), java.util.List.of());
        TableSchema orders = new TableSchema("orders",
                java.util.List.of(new ColumnSchema("id", "BIGINT", false, true),
                        new ColumnSchema("user_id", "VARCHAR", false, false),
                        new ColumnSchema("status", "VARCHAR", false, false)),
                java.util.List.of(new ForeignKey("user_id", "users", "id")),
                java.util.List.of());

        SynthesizedInput out = new ReadInputSynthesizer().synthesize(endpoint, java.util.List.of(orders, users));

        // parent users seeded before child orders
        java.util.List<String> tableOrder = out.seeds().stream()
                .map(SynthesizedInput.SeedRow::table).toList();
        assertThat(tableOrder).containsExactly("users", "orders");
        // child FK value == parent PK value
        SynthesizedInput.SeedRow users_ = out.seeds().get(0);
        SynthesizedInput.SeedRow orders_ = out.seeds().get(1);
        int fkIdx = orders_.columns().indexOf("user_id");
        int pkIdx = users_.columns().indexOf("id");
        assertThat(orders_.values().get(fkIdx)).isEqualTo(users_.values().get(pkIdx));
    }

    @Test
    void pkColumnIsFirstEvenWhenSchemaListsItLater() {
        Endpoint endpoint = new Endpoint("get-api-orders-id", "GET", "/api/orders/{id}",
                "x.C", "get", java.util.List.of(new EndpointParam("id", "java.lang.Long", ParamKind.PATH)),
                true);
        // schema lists a NOT NULL column ("created_at") BEFORE the PK ("id")
        TableSchema orders = new TableSchema("orders",
                java.util.List.of(
                        new ColumnSchema("created_at", "TIMESTAMP", false, false),
                        new ColumnSchema("id", "BIGINT", false, true),
                        new ColumnSchema("status", "VARCHAR", false, false)),
                java.util.List.of(), java.util.List.of());

        SynthesizedInput out = new ReadInputSynthesizer().synthesize(endpoint, java.util.List.of(orders));

        SynthesizedInput.SeedRow seed = out.seeds().get(out.seeds().size() - 1); // target row
        assertThat(seed.columns().get(0)).isEqualTo("id");                       // PK first
        assertThat(seed.columns()).containsExactlyInAnyOrder("created_at", "id", "status"); // same columns
    }

    @Test
    void pathVariableSeedsTargetTableAndBuildsInput() {
        Endpoint endpoint = new Endpoint("get-api-orders-id", "GET", "/api/orders/{id}",
                "x.C", "get", List.of(new EndpointParam("id", "java.lang.Long", ParamKind.PATH)),
                true);
        TableSchema orders = new TableSchema("orders",
                List.of(new ColumnSchema("id", "BIGINT", false, true),
                        new ColumnSchema("status", "VARCHAR", false, false)),
                List.of(), List.of());

        SynthesizedInput out = new ReadInputSynthesizer().synthesize(endpoint, List.of(orders));

        assertThat(out.seeds()).hasSize(1);
        SynthesizedInput.SeedRow seed = out.seeds().get(0);
        assertThat(seed.table()).isEqualTo("orders");
        assertThat(seed.columns()).contains("id");
        // bigint PK 컬럼이므로 seed 값은 문자열이 아니라 Long으로 강제된다 (varchar→bigint INSERT 방지)
        int idIdx = seed.columns().indexOf("id");
        assertThat(seed.values().get(idIdx)).isInstanceOf(Long.class);
        assertThat((Long) seed.values().get(idIdx)).isGreaterThanOrEqualTo(90001L);
        // PATH 변수는 PK 셀렉터 → URL 값(body)과 시드 PK가 일치해야 한다
        assertThat(out.body().get("id").asText()).isEqualTo(String.valueOf(seed.values().get(idIdx)));
    }

    @Test
    void stringPkSeedIsEndpointUniqueAndMatchesPathValue() {
        // 같은 varchar-PK 테이블(post)을 읽는 두 엔드포인트가 서로 다른 PK로 시드해야
        // 병렬 실행 시 PK 충돌하지 않고, 한쪽이 시드한 행을 다른 쪽이 우연히 읽지 않는다.
        TableSchema post = new TableSchema("post",
                List.of(new ColumnSchema("id", "VARCHAR", false, true),
                        new ColumnSchema("title", "VARCHAR", false, false)),
                List.of(), List.of());
        Endpoint a = new Endpoint("get-internal-posts-id", "GET", "/internal/posts/{id}",
                "x.C", "get", List.of(new EndpointParam("id", "java.lang.String", ParamKind.PATH)), true);
        Endpoint b = new Endpoint("get-internal-posts-id-content", "GET", "/internal/posts/{id}/content",
                "x.C", "get", List.of(new EndpointParam("id", "java.lang.String", ParamKind.PATH)), true);

        SynthesizedInput outA = new ReadInputSynthesizer().synthesize(a, List.of(post));
        SynthesizedInput outB = new ReadInputSynthesizer().synthesize(b, List.of(post));

        String pkA = outA.seeds().get(0).values().get(outA.seeds().get(0).columns().indexOf("id")).toString();
        String pkB = outB.seeds().get(0).values().get(outB.seeds().get(0).columns().indexOf("id")).toString();
        assertThat(pkA).isNotEqualTo(pkB);                              // 엔드포인트별 비충돌
        assertThat(outA.body().get("id").asText()).isEqualTo(pkA);      // URL 값 == 시드 PK
        assertThat(outB.body().get("id").asText()).isEqualTo(pkB);
    }

    @Test
    void synthesize_enumQueryParam_returnsFirstConstant() {
        Map<String, List<String>> enums = Map.of("io.x.Palette", List.of("RED", "GREEN"));
        Endpoint endpoint = new Endpoint("get-api-items", "GET", "/api/items", "x.C", "get",
                List.of(new EndpointParam("palette", "io.x.Palette", ParamKind.QUERY)), false);
        SynthesizedInput out = new ReadInputSynthesizer(enums).synthesize(endpoint, List.of());
        assertThat(out.body().get("palette").asText()).isEqualTo("RED");
    }

    @Test
    void synthesize_booleanQueryParam_returnsTrue() {   // Bug 2
        Endpoint endpoint = new Endpoint("get-api-items-id", "GET", "/api/items/{id}", "x.C", "get",
                List.of(new EndpointParam("id", "int", ParamKind.PATH),
                        new EndpointParam("includeStale", "boolean", ParamKind.QUERY)), false);
        TableSchema items = new TableSchema("items",
                List.of(new ColumnSchema("id", "INT", false, true)), List.of(), List.of());
        SynthesizedInput out = new ReadInputSynthesizer().synthesize(endpoint, List.of(items));
        assertThat(out.body().get("includeStale").asText()).isEqualTo("true");
    }

    @Test
    void synthesize_enumColumn_seededWithValidConstantNotProbe() {   // Bug 3
        Endpoint endpoint = new Endpoint("get-api-orders-id", "GET", "/api/orders/{id}", "x.C", "get",
                List.of(new EndpointParam("id", "int", ParamKind.PATH)), false);
        TableSchema orders = new TableSchema("orders",
                List.of(new ColumnSchema("id", "INT", false, true),
                        new ColumnSchema("status", "VARCHAR", false, false)),   // enum 컬럼 NOT NULL
                List.of(), List.of());
        Map<String, List<String>> enumCols = Map.of("status", List.of("PENDING", "CONFIRMED"));
        SynthesizedInput out = new ReadInputSynthesizer(Map.of(), enumCols).synthesize(endpoint, List.of(orders));
        SynthesizedInput.SeedRow seed = out.seeds().get(0);
        int idx = seed.columns().indexOf("status");
        assertThat(seed.values().get(idx)).isEqualTo("PENDING");   // "probe" 아님 (읽기 500 방지)
    }
}
