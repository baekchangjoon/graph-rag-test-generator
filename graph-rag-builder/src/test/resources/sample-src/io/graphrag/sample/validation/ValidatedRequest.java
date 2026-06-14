package io.graphrag.sample.validation;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ValidatedRequest(
        @NotBlank @Size(min = 2, max = 10) String name,
        @Min(1) @Max(100) Integer quantity,
        @Positive Integer price,
        @Email String contact,
        @Pattern(regexp = "[A-Z]{3}") String code) {
}
