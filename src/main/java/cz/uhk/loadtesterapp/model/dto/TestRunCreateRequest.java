package cz.uhk.loadtesterapp.model.dto;

import cz.uhk.loadtesterapp.model.enums.ProcessingMode;
import cz.uhk.loadtesterapp.model.enums.TestScenario;

public record TestRunCreateRequest( @jakarta.validation.constraints.NotNull TestScenario testScenario,
                                     Integer totalRequests,
                                     Integer concurrency,
                                    @jakarta.validation.constraints.NotNull ProcessingMode processingMode,
                                    Integer poolSizeOrCap,
                                    Long delayMs,
                                    @jakarta.validation.constraints.NotNull RequestDefinitionDto request) {
}

