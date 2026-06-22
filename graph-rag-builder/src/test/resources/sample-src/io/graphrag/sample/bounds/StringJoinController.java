package io.graphrag.sample.bounds;

public class StringJoinController {
    public record Req(String a, String b) {}
    public String handle(Req req) {
        if (req.a().equals(req.b())) { return "same"; }
        return "diff";
    }
}
