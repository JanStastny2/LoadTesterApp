package cz.uhk.loadtesterapp.repository;

import cz.uhk.loadtesterapp.model.entity.TestRunHwSample;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestRunHwSampleRepository extends JpaRepository<TestRunHwSample, Long> {

    List<TestRunHwSample> findByTestRun_IdOrderByTsAsc(Long testRunId);

}
