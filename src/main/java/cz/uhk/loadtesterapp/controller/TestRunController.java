package cz.uhk.loadtesterapp.controller;

import cz.uhk.loadtesterapp.mapper.TestMapper;
import cz.uhk.loadtesterapp.model.dto.*;
import cz.uhk.loadtesterapp.model.entity.CancellationRegistry;
import cz.uhk.loadtesterapp.model.entity.TestRun;
import cz.uhk.loadtesterapp.model.enums.ProcessingMode;
import cz.uhk.loadtesterapp.model.enums.TestScenario;
import cz.uhk.loadtesterapp.model.enums.TestStatus;
import cz.uhk.loadtesterapp.service.TestCommandService;
import cz.uhk.loadtesterapp.service.TestRunQueryService;
import cz.uhk.loadtesterapp.service.TestRunnerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/tests")
@RequiredArgsConstructor

public class TestRunController {
    private static final Logger log = LoggerFactory.getLogger(TestRunController.class);


    private final TestRunnerService runnerService;
    private final TestRunQueryService queryService;
    private final TestCommandService commandService;
    private final CancellationRegistry cancellationRegistry;
    private final TestRunQueryService testRunQueryService;
    private final TestMapper testMapper;

    @PostMapping
    public ResponseEntity<TestRunResponse> create(@Valid @RequestBody TestRunCreateRequest req, Authentication auth) {
        log.info("Creating test run: {}", req);
        if (req.processingMode() == ProcessingMode.SERIAL && req.poolSizeOrCap() != 1) {
            log.warn("When processing mode is SERIAL, the pool size must be 1");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        var toCreate = testMapper.toEntity(req);
        var saved = commandService.create(toCreate, auth);
        return ResponseEntity.status(HttpStatus.CREATED).body(testMapper.toResponse(saved));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/run")
    public ResponseEntity<?> run(@PathVariable Long id) {
        if (queryService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        runnerService.run(id).subscribe();
        return ResponseEntity.accepted().build();
    }


//    @GetMapping
//    public ResponseEntity<List<TestRunResponse>> getAll(Authentication auth) {
//        var list = queryService.list(auth).stream().map(testMapper::toResponse).toList();
//        return ResponseEntity.ok(list);
//    }


    @GetMapping()
    public ResponseEntity<Page<TestRunResponse>> list(
            Authentication auth,
            @RequestParam(required = false) TestStatus status,
            @RequestParam(required = false) ProcessingMode mode,
            @RequestParam(required = false) Integer poolSizeOrCap,
            @RequestParam(required = false) TestScenario testScenario,
            @RequestParam(required = false) Integer totalRequests,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Long createdById,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<TestRun> testRunPage = testRunQueryService.search(
                auth, status, mode, poolSizeOrCap, testScenario, totalRequests, username, createdById, from, to, pageable
        );

        Page<TestRunResponse> responsePage = testRunPage.map(testMapper::toResponse);
        return ResponseEntity.ok(responsePage);
    }

    @GetMapping("/compare")
    public ResponseEntity<List<TestCompareItem>> compare(@RequestParam("ids") List<Long> ids) {
        List<TestRun> runs = testRunQueryService.findAllById(ids);
        var items = runs.stream().map(t -> new TestCompareItem(
                t.getId(),
                t.getTestScenario(),
                t.getStatus(),
                t.getProcessingMode(),
                t.getConcurrency(),
                t.getPoolSizeOrCap(),
                t.getDelayMs(),
                t.getEffectiveUrl(),
                t.getSummary() != null ? t.getSummary().getSuccesses() : null,
                t.getSummary() != null ? t.getSummary().getFailures() : null,
                t.getSummary() != null ? t.getSummary().getSuccessRate() : null,
                t.getSummary() != null ? t.getSummary().getDurationMs() : null,
                t.getSummary() != null ? t.getSummary().getThroughputRps() : null,
                t.getSummary() != null ? t.getSummary().getAvgResponseTimeMs() : null,
                t.getSummary() != null ? t.getSummary().getP95ResponseTimeMs() : null,
                t.getSummary() != null ? t.getSummary().getAvgServerProcessingMs() : null,
                t.getSummary() != null ? t.getSummary().getAvgQueueWaitMs() : null,
                t.getCreatedAt()
        )).toList();
        return ResponseEntity.ok(items);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TestRunResponse> detail(@PathVariable Long id) {
        return queryService.findById(id)
                .map(testMapper::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<TestRunResponse> update(@PathVariable Long id,
                                                  @Valid @RequestBody TestUpdateRequest update) {
        if (update.processingMode() == ProcessingMode.SERIAL && update.poolSizeOrCap() != 1) {
            log.warn("When processing mode is SERIAL, the pool size must be 1");
            return ResponseEntity.badRequest().build();
        }
        var existing = queryService.findById(id);
        if (existing.isEmpty())
            return ResponseEntity.notFound().build();

        var updated = commandService.update(id, update);
        return updated.map(testMapper::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        boolean deleted = commandService.deleteById(id);
        return deleted ? ResponseEntity.ok().build() :
                ResponseEntity.notFound().build();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/approve")
    public ResponseEntity<TestRunResponse> approve(@PathVariable Long id) {
        return commandService.approve(id)
                .map(testMapper::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/reject")
    public ResponseEntity<TestRunResponse> reject(@PathVariable Long id) {
        return commandService.reject(id)
                .map(testMapper::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable Long id) {
        var test = queryService.findById(id);
        if (test.isEmpty())
            return ResponseEntity.notFound().build();

        var e = test.get();
        if (e.getStatus() == TestStatus.WAITING) {
            commandService.cancel(id);
            return ResponseEntity.ok().build();
        }
        if (e.getStatus() == TestStatus.RUNNING) {
            cancellationRegistry.requestCancel(id);
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @GetMapping("/{id}/hw-samples")
    public ResponseEntity<List<HwSampleDto>> hwSeries(@PathVariable Long id) {
        var samples = queryService.getHwSamples(id);
        return ResponseEntity.ok(samples);
    }

}
