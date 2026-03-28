package cz.uhk.loadtesterapp.model.dto;

public record AdminDashboardStatsDto(long totalTests,
                                     long totalUsers,
                                     long totalSucceeded,
                                     long totalFailed,
                                     long countCreated,
                                     long countApproved,
                                     long countRunning) {
}
