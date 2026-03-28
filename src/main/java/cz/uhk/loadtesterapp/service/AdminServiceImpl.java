package cz.uhk.loadtesterapp.service;

import cz.uhk.loadtesterapp.model.dto.AdminDashboardStatsDto;
import cz.uhk.loadtesterapp.repository.TestRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final TestRepository testRepository;

    @Override
    public AdminDashboardStatsDto getDashboardStats() {
        return testRepository.getDashboardStats();
    }
}
