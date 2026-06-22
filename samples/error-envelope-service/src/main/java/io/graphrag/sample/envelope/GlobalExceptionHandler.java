package io.graphrag.sample.envelope;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ErrorEnvelope> handleBizException(BizException ex) {
        ErrorEnvelope body = new ErrorEnvelope(
                "ERROR",
                ex.getErrorCode(),
                ex.getMessage(),
                ex.getClass().getSimpleName() + ": " + ex.getMessage()
        );
        // Always HTTP 200 — the envelope carries the logical error code
        return ResponseEntity.ok(body);
    }

    public record ErrorEnvelope(
            String errorServer,
            String errorCode,
            String errorMsg,
            String errorDetail
    ) {}
}
