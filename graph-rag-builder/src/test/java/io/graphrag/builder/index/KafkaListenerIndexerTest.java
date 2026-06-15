package io.graphrag.builder.index;

import io.graphrag.model.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaListenerIndexerTest {

    private static Path writeConsumer(Path dir, String topicExpr) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("com/x"));
        Files.writeString(pkg.resolve("CommentEvent.java"),
                "package com.x;\n"
                        + "public record CommentEvent(String eventId, String postAuthorUserId, String role) {}\n");
        Files.writeString(pkg.resolve("Consumer.java"),
                "package com.x;\n"
                        + "import org.springframework.kafka.annotation.KafkaListener;\n"
                        + "public class Consumer {\n"
                        + "  @KafkaListener(topics = " + topicExpr + ", groupId = \"grp\")\n"
                        + "  public void onCommentCreated(CommentEvent event) {}\n"
                        + "}\n");
        return dir;
    }

    @Test
    void index_literalTopic_extractsConsumerAndPayloadShape(@TempDir Path dir) throws Exception {
        KafkaIndexResult result = new KafkaListenerIndexer().index(writeConsumer(dir, "\"comment.created\""));

        assertThat(result.consumers()).hasSize(1);
        KafkaConsumer c = result.consumers().get(0);
        assertThat(c.id()).isEqualTo("kafka-comment-created");
        assertThat(c.topic()).isEqualTo("comment.created");
        assertThat(c.groupId()).isEqualTo("grp");
        assertThat(c.handlerClass()).isEqualTo("com.x.Consumer");
        assertThat(c.handlerMethod()).isEqualTo("onCommentCreated");
        assertThat(c.payloadType()).isEqualTo("com.x.CommentEvent");
        assertThat(result.payloadShapes().get("com.x.CommentEvent").fields())
                .extracting(BodyShape.BodyField::name)
                .contains("eventId", "postAuthorUserId", "role");
    }

    @Test
    void index_propTopic_preservedVerbatim(@TempDir Path dir) throws Exception {
        KafkaIndexResult result = new KafkaListenerIndexer()
                .index(writeConsumer(dir, "\"${mindgraph.topics.diary-created}\""));
        assertThat(result.consumers().get(0).topic()).isEqualTo("${mindgraph.topics.diary-created}");
    }

    @Test
    void index_noListener_empty(@TempDir Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("com/x"));
        Files.writeString(pkg.resolve("Plain.java"), "package com.x;\npublic class Plain { public void f(){} }\n");
        assertThat(new KafkaListenerIndexer().index(dir).consumers()).isEmpty();
    }
}
