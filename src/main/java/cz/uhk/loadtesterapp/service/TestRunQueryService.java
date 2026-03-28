package cz.uhk.loadtesterapp.service;

import cz.uhk.loadtesterapp.model.dto.HwSampleDto;
import cz.uhk.loadtesterapp.model.entity.TestRun;
import cz.uhk.loadtesterapp.model.entity.User;
import cz.uhk.loadtesterapp.model.enums.ProcessingMode;
import cz.uhk.loadtesterapp.model.enums.TestScenario;
import cz.uhk.loadtesterapp.model.enums.TestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TestRunQueryService {

//    List<TestRun> list(Authentication auth);

    List<TestRun> findAllById(List<Long> ids);

    Optional<TestRun> findById(Long id);

    List<HwSampleDto> getHwSamples(Long testRunId);

    Page<TestRun> search(
            Authentication auth,
            TestStatus status,
            ProcessingMode mode,
            Integer poolSizeOrCap,
            TestScenario testScenario,
            Integer totalRequests,
            String username,
            Long createdById,
            Instant from,
            Instant to,
            Pageable pageable);

}
