package io.graphrag.builder.config;

import io.graphrag.builder.persistence.GraphArchive;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class BuilderConfig {

    @Bean
    public GraphArchive graphArchive(@Value("${graph.archive.dir:.graph-rag-cache}") String dir)
            throws IOException {
        Path base = Paths.get(dir);
        return GraphArchive.load(base);
    }
}
