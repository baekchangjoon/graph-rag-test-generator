package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.KafkaConsumer;
import io.graphrag.model.ParamKind;
import io.graphrag.model.WsEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 4: collection-aware body/payload extraction across the three indexers. */
class EndpointIndexerCollectionTest {

    @Test
    void http_requestBodyListOfDto_keyedByEncodedKey_withCollectionShape(@TempDir Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("p"));
        Files.writeString(pkg.resolve("Dto.java"),
                "package p;\npublic record Dto(String name, int qty) {}\n");
        Files.writeString(pkg.resolve("C.java"),
                "package p;\n"
                        + "import java.util.List;\n"
                        + "import org.springframework.web.bind.annotation.*;\n"
                        + "@RestController\n"
                        + "class C {\n"
                        + "  @PostMapping(\"/batch\")\n"
                        + "  void batch(@RequestBody List<Dto> rs) {}\n"
                        + "}\n");

        IndexResult result = new EndpointIndexer().index(dir, null);

        Endpoint batch = result.endpoints().stream()
                .filter(e -> e.path().equals("/batch") && e.httpMethod().equals("POST"))
                .findFirst().orElseThrow();
        EndpointParam body = batch.params().stream()
                .filter(p -> p.kind() == ParamKind.BODY).findFirst().orElseThrow();

        String key = "java.util.List<p.Dto>";
        assertThat(body.javaType()).isEqualTo(key);

        BodyShape shape = result.bodyShapes().get(key);
        assertThat(shape).isNotNull();
        assertThat(shape.collection()).isTrue();
        assertThat(shape.fields()).extracting(BodyShape.BodyField::name)
                .containsExactly("name", "qty");
    }

    @Test
    void kafka_listenerListOfDto_keyedByEncodedKey_withCollectionShape(@TempDir Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("p"));
        Files.writeString(pkg.resolve("Evt.java"),
                "package p;\npublic record Evt(String id, String role) {}\n");
        Files.writeString(pkg.resolve("Consumer.java"),
                "package p;\n"
                        + "import java.util.List;\n"
                        + "import org.springframework.kafka.annotation.KafkaListener;\n"
                        + "public class Consumer {\n"
                        + "  @KafkaListener(topics = \"evt.batch\", groupId = \"g\")\n"
                        + "  public void on(List<Evt> events) {}\n"
                        + "}\n");

        KafkaIndexResult result = new KafkaListenerIndexer().index(dir);

        KafkaConsumer c = result.consumers().get(0);
        String key = "java.util.List<p.Evt>";
        assertThat(c.payloadType()).isEqualTo(key);
        BodyShape shape = result.payloadShapes().get(key);
        assertThat(shape).isNotNull();
        assertThat(shape.collection()).isTrue();
        assertThat(shape.fields()).extracting(BodyShape.BodyField::name).containsExactly("id", "role");
    }

    @Test
    void ws_messageMappingListOfDto_keyedByEncodedKey_withCollectionShape(@TempDir Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("p"));
        Files.writeString(pkg.resolve("Msg.java"),
                "package p;\npublic record Msg(String text) {}\n");
        Files.writeString(pkg.resolve("Handler.java"),
                "package p;\n"
                        + "import java.util.List;\n"
                        + "import org.springframework.messaging.handler.annotation.MessageMapping;\n"
                        + "public class Handler {\n"
                        + "  @MessageMapping(\"/chat\")\n"
                        + "  public void on(List<Msg> msgs) {}\n"
                        + "}\n");

        WsIndexResult result = new WsEndpointIndexer().index(dir);

        WsEndpoint e = result.endpoints().get(0);
        String key = "java.util.List<p.Msg>";
        assertThat(e.payloadType()).isEqualTo(key);
        BodyShape shape = result.payloadShapes().get(key);
        assertThat(shape).isNotNull();
        assertThat(shape.collection()).isTrue();
        assertThat(shape.fields()).extracting(BodyShape.BodyField::name).containsExactly("text");
    }
}
