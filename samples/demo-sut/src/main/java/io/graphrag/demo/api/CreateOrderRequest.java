package io.graphrag.demo.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateOrderRequest(
        @NotBlank String userId,
        @NotNull Long amount,
        @NotBlank String type) {}
