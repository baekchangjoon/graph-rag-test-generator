package io.graphrag.builder.capture.zipkin;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.graphrag.builder.capture.otlp.SpanRecord;
import io.graphrag.builder.capture.trace.TraceReceiverLimits;
import io.graphrag.model.Json;
import org.slf4j.*;
import java.io.*; import java.net.*; import java.util.*; import java.util.concurrent.*; import java.util.zip.GZIPInputStream;
public final class ZipkinSpanReceiver implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ZipkinSpanReceiver.class);
    private static final int MAX_BODY = 5*1024*1024;
    private HttpServer server;
    private final Map<String,List<SpanRecord>> byTrace = new ConcurrentHashMap<>();
    private final Map<String,Long> lastArrival = new ConcurrentHashMap<>();
    public void start(){ start(null); }
    public void start(String bindHost){
        InetAddress addr=(bindHost==null||bindHost.isBlank())?InetAddress.getLoopbackAddress():resolve(bindHost);
        try {
            server=HttpServer.create(new InetSocketAddress(addr,0),0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/api/v2/spans", ex -> {
                try { ingest(readBody(ex)); ex.sendResponseHeaders(202,-1); }
                catch (Exception e){ log.warn("zipkin ingest failed",e); try { ex.sendResponseHeaders(500,-1);} catch(IOException ignore){} }
                finally { ex.close(); }
            });
            server.start(); log.info("zipkin receiver on {}",endpoint());
        } catch (IOException e){ throw new UncheckedIOException("zipkin receiver start",e); }
    }
    private static byte[] readBody(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        byte[] raw=ex.getRequestBody().readNBytes(MAX_BODY);
        if ("gzip".equalsIgnoreCase(ex.getRequestHeaders().getFirst("Content-Encoding")))
            try (var gz=new GZIPInputStream(new ByteArrayInputStream(raw))){ return gz.readAllBytes(); }
        return raw;
    }
    private void ingest(byte[] body) throws IOException {
        JsonNode arr=Json.mapper().readTree(body); if (!arr.isArray()) return;
        for (JsonNode n:arr) record(toRecord(n));
    }
    private static SpanRecord toRecord(JsonNode n){
        Map<String,String> tags=new LinkedHashMap<>();
        JsonNode t=n.get("tags"); if (t!=null) t.fields().forEachRemaining(e->tags.put(e.getKey(),e.getValue().asText()));
        return new SpanRecord(n.path("traceId").asText(""), n.path("id").asText(""), n.path("parentId").asText(""),
            n.path("name").asText(""), n.path("kind").asText(""), n.path("timestamp").asLong(0)*1000L, tags, List.of());
    }
    private void record(SpanRecord s){
        if (!TraceReceiverLimits.HEX_32.matcher(s.traceId()).matches()) return;
        if (!byTrace.containsKey(s.traceId())) evictIfFull();   // evict는 computeIfAbsent 밖
        List<SpanRecord> spans=byTrace.computeIfAbsent(s.traceId(), k->new CopyOnWriteArrayList<>());
        if (spans.size()>=TraceReceiverLimits.MAX_SPANS_PER_TRACE) return;
        spans.add(s); lastArrival.put(s.traceId(),System.nanoTime());
    }
    private void evictIfFull(){ if (byTrace.size()<TraceReceiverLimits.MAX_TRACES) return;
        lastArrival.entrySet().stream().min(Map.Entry.comparingByValue()).ifPresent(e->remove(e.getKey())); }
    public List<SpanRecord> spans(String t){ return List.copyOf(byTrace.getOrDefault(t,List.of())); }
    public boolean isQuiescent(String t,long ms){ Long l=lastArrival.get(t); return l!=null&&(System.nanoTime()-l)>=ms*1_000_000L; }
    public void remove(String t){ byTrace.remove(t); lastArrival.remove(t); }
    public String endpoint(){ return "http://127.0.0.1:"+port(); }
    public String hostEndpoint(){ return "http://host.docker.internal:"+port(); }
    public int port(){ return server.getAddress().getPort(); }
    public void stop(){ if (server!=null) server.stop(0); }
    @Override public void close(){ stop(); }
    private static InetAddress resolve(String h){ try { return InetAddress.getByName(h);} catch(IOException e){ throw new UncheckedIOException(e);} }
}
