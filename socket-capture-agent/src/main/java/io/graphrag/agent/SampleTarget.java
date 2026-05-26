package io.graphrag.agent;

/**
 * Agent instrumentation 시연용 타겟 클래스.
 *
 * <p>{@link AgentMain}이 attach되면 {@code invoke()} 호출 시 {@link CaptureCounter}가 증가.
 */
public class SampleTarget {

    public String invoke(String arg) {
        return "handled:" + arg;
    }
}
