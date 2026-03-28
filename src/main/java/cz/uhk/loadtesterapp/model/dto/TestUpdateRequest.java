package cz.uhk.loadtesterapp.model.dto;


import cz.uhk.loadtesterapp.model.enums.ProcessingMode;
import cz.uhk.loadtesterapp.model.enums.TestScenario;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;


public record TestUpdateRequest(
         Integer totalRequests,
        @NotNull TestScenario testScenario,
         Integer concurrency,
        @NotNull ProcessingMode processingMode,
        @Min(1) Integer poolSizeOrCap,
        @NotNull RequestDefinitionDto request,
        @Min(0) Long delayMs
)
{ }
