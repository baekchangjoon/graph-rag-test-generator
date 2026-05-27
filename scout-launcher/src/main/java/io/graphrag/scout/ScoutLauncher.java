package io.graphrag.scout;

import io.graphrag.scout.config.ConfigLoader;
import io.graphrag.scout.config.ScoutConfig;
import io.graphrag.scout.orchestrate.PipelineRunner;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CLI: {@code java -jar scout-launcher.jar [config.yml]}.
 *
 * <p>Default config path is {@code ./config.yml}. The launcher orchestrates docker
 * compose dependencies, the SUT JVM (with optional agents), and HTTP scout requests
 * — see {@code docs/20-scout-launcher.md} and {@code samples/scout/petclinic/config.yml}.
 */
public final class ScoutLauncher {

    private ScoutLauncher() {}

    public static void main(String[] args) throws Exception {
        Path cfgPath = Paths.get(args.length > 0 ? args[0] : "config.yml").toAbsolutePath();
        System.out.println("[scout] config: " + cfgPath);
        ScoutConfig cfg = ConfigLoader.load(cfgPath);
        new PipelineRunner(cfg).run();
        System.out.println("[scout] done");
    }
}
