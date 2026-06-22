package io.graphrag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 에러 계약 디스크립터(semanticStatusField/errorDetailField/errorDetailContains) 후방 호환. */
class GraphAssetCompatTest {

    private static final ObjectMapper MAPPER = Json.mapper();

    /** 14-arg compat 생성자(에러 계약 디스크립터 생략) → 세 필드 모두 null */
    @Test
    void legacy14ArgConstructorDefaultsErrorContractDescriptorToNull() {
        GraphAsset asset = new GraphAsset("sut", "sha",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(asset.semanticStatusField()).isNull();
        assertThat(asset.errorDetailField()).isNull();
        assertThat(asset.errorDetailContains()).isNull();
    }

    /** 13-arg compat 생성자(capturedEventEmits·에러 계약 모두 생략)도 컴파일·동작 */
    @Test
    void legacy13ArgConstructorStillWorks() {
        GraphAsset asset = new GraphAsset("sut", "sha",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(asset.capturedEventEmits()).isEmpty();
        assertThat(asset.semanticStatusField()).isNull();
    }

    /** 신규 17-arg 정규 생성자: 값 보존 */
    @Test
    void canonicalConstructorPreservesErrorContractDescriptor() {
        GraphAsset asset = new GraphAsset("sut", "sha",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "errorCode", "errorDetail", "BizException");
        assertThat(asset.semanticStatusField()).isEqualTo("errorCode");
        assertThat(asset.errorDetailField()).isEqualTo("errorDetail");
        assertThat(asset.errorDetailContains()).isEqualTo("BizException");
    }

    /** 역직렬화 후방호환: 새 필드가 없는 legacy graph.json → 세 필드 null */
    @Test
    void jacksonDeserializeLegacyJsonMissingErrorContractFieldsToNull() throws Exception {
        String json = """
                {"sutId":"sut","commitSha":"sha","endpoints":[],"paths":[],"sql":[],
                 "tables":[],"mappers":[],"httpCalls":[],"wsEndpoints":[],"wsExchanges":[],
                 "kafkaConsumers":[],"kafkaExchanges":[],"seeds":[],"capturedEventEmits":[]}
                """;
        GraphAsset asset = MAPPER.readValue(json, GraphAsset.class);
        assertThat(asset.semanticStatusField()).isNull();
        assertThat(asset.errorDetailField()).isNull();
        assertThat(asset.errorDetailContains()).isNull();
    }

    /** 신규 형식(세 필드 명시) → 값 보존 */
    @Test
    void jacksonDeserializeNewJsonPreservesErrorContractFields() throws Exception {
        String json = """
                {"sutId":"sut","commitSha":"sha","endpoints":[],"paths":[],"sql":[],
                 "tables":[],"mappers":[],"httpCalls":[],"wsEndpoints":[],"wsExchanges":[],
                 "kafkaConsumers":[],"kafkaExchanges":[],"seeds":[],"capturedEventEmits":[],
                 "semanticStatusField":"errorCode","errorDetailField":"errorDetail",
                 "errorDetailContains":"BizException"}
                """;
        GraphAsset asset = MAPPER.readValue(json, GraphAsset.class);
        assertThat(asset.semanticStatusField()).isEqualTo("errorCode");
        assertThat(asset.errorDetailField()).isEqualTo("errorDetail");
        assertThat(asset.errorDetailContains()).isEqualTo("BizException");
    }
}
