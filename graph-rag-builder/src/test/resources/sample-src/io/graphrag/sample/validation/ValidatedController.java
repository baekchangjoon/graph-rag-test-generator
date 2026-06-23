package io.graphrag.sample.validation;

public class ValidatedController {
    public String create(ValidatedRequest req) {
        if (req.code().startsWith("ABC")) {
            return "special";
        }
        return "ok";
    }
}
