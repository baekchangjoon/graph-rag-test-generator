package io.graphrag.builder.coverage;

import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecDumpClient;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.IOException;
import java.io.UncheckedIOException;

/** 실행 중 SUT의 jacoco agent에서 TCP로 실행 데이터를 회수한다. */
public class CoverageClient {

    private final String host;
    private final int port;

    public CoverageClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** @param reset true면 dump 후 agent 카운터 리셋 (요청 단위 novelty 측정용) */
    public ExecutionDataStore dump(boolean reset) {
        ExecDumpClient client = new ExecDumpClient();
        client.setDump(true);
        client.setReset(reset);
        client.setRetryCount(5);
        try {
            ExecFileLoader loader = client.dump(host, port);
            return loader.getExecutionDataStore();
        } catch (IOException e) {
            throw new UncheckedIOException("jacoco dump failed (" + host + ":" + port + ")", e);
        }
    }
}
