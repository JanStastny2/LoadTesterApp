package cz.uhk.loadtesterapp.model.entity;


import jakarta.persistence.Embeddable;
import lombok.*;


@AllArgsConstructor
@NoArgsConstructor
@Builder
@Embeddable
@Getter
@Setter
@EqualsAndHashCode
public class TestSummary {
    private int successes;
    private int failures;
    private double successRate;
    private long durationMs;
    private double throughputRps;

    private double avgResponseTimeMs;
    private double p95ResponseTimeMs;

    private Double avgServerProcessingMs;
    private Double p95ServerProcessingMs;
    private Double avgQueueWaitMs;
    private Double p95QueueWaitMs;
}
