package cz.uhk.loadtesterapp.service;

import cz.uhk.loadtesterapp.mapper.TestMapper;
import cz.uhk.loadtesterapp.model.dto.HwSampleDto;
import cz.uhk.loadtesterapp.model.entity.TestRun;
import cz.uhk.loadtesterapp.model.entity.User;
import cz.uhk.loadtesterapp.model.enums.ProcessingMode;
import cz.uhk.loadtesterapp.model.enums.TestScenario;
import cz.uhk.loadtesterapp.model.enums.TestStatus;
import cz.uhk.loadtesterapp.repository.TestRepository;
import cz.uhk.loadtesterapp.repository.TestRunHwSampleRepository;
import cz.uhk.loadtesterapp.security.MyUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import jakarta.persistence.criteria.Predicate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TestRunQueryServiceImpl implements TestRunQueryService {

    private final TestRepository testRepository;
    private final TestMapper testMapper;
    private final TestRunHwSampleRepository TestRunHwSampleRepository;


    @Override
    public Page<TestRun> search(
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
            Pageable pageable) {

        Specification<TestRun> spec = (root, query, cb) -> {

            List<Predicate> predicates = new ArrayList<>();

            boolean admin = auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

            if (!admin) {
                var principal = (MyUserDetails) auth.getPrincipal();
                predicates.add(cb.equal(root.get("createdBy").get("id"), principal.getUserId()));
            } else {
                if (createdById != null) {
                    predicates.add(cb.equal(root.get("createdBy").get("id"), createdById));
                }
                if (username != null) {
                    predicates.add(cb.equal(root.get("createdBy").get("username"), username));                }
            }

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (mode != null) {
                predicates.add(cb.equal(root.get("processingMode"), mode));
            }
            if (poolSizeOrCap != null) {
                predicates.add(cb.equal(root.get("poolSizeOrCap"), poolSizeOrCap));
            }
            if (testScenario != null) {
                predicates.add(cb.equal(root.get("testScenario"), testScenario));
            }
            if (totalRequests != null) {
                predicates.add(cb.equal(root.get("totalRequests"), totalRequests));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return testRepository.findAll(spec, pageable);
    }


    @Override
    public List<TestRun> findAllById(List<Long> ids) {
        return testRepository.findAllById(ids);
    }


    @Override
    public Optional<TestRun> findById(Long id) {
        return testRepository.findById(id);
    }

    @Override
    public List<HwSampleDto> getHwSamples(Long testRunId) {
        return testMapper.toSampleDto(
                TestRunHwSampleRepository.findByTestRun_IdOrderByTsAsc(testRunId)
        );
    }
}
