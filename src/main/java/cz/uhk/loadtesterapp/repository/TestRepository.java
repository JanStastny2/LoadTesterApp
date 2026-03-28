package cz.uhk.loadtesterapp.repository;


import cz.uhk.loadtesterapp.model.dto.AdminDashboardStatsDto;
import cz.uhk.loadtesterapp.model.entity.TestRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;


@Repository
public interface TestRepository extends JpaRepository<TestRun, Long>, JpaSpecificationExecutor<TestRun> {

    @Query("SELECT new cz.uhk.loadtesterapp.model.dto.AdminDashboardStatsDto(" +
            "COUNT(t.id), " +
            "(SELECT COUNT(u.id) FROM User u), " +
            "SUM(CASE WHEN t.status = 'FINISHED' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN t.status = 'FAILED' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN t.status = 'CREATED' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN t.status = 'APPROVED' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN t.status = 'RUNNING' THEN 1 ELSE 0 END)" +
            ") FROM TestRun t")
    AdminDashboardStatsDto getDashboardStats();
}

