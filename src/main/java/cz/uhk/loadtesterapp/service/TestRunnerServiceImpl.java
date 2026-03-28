package cz.uhk.loadtesterapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.uhk.loadtesterapp.model.entity.*;
import cz.uhk.loadtesterapp.model.enums.ProcessingMode;
import cz.uhk.loadtesterapp.model.enums.TestScenario;
import cz.uhk.loadtesterapp.model.enums.TestStatus;
import cz.uhk.loadtesterapp.repository.TestRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TestRunnerServiceImpl implements TestRunnerService {

    private static final Logger log = LoggerFactory.getLogger(TestRunnerServiceImpl.class);

    private final ApiRequestService apiRequestService;
    private final TestRepository testRepository;
    private final ObjectMapper objectMapper;
    private final CancellationRegistry cancels;
    private final HwSampleService hwSampleService;
    private final TransactionTemplate txTemplate;

    private final Duration interval = Duration.ofMillis(250);

    private static final int RAMP_START_CONCURRENCY = 10;
    private static final int RAMP_STEP = 30;
    private static final Duration RAMP_STEP_EVERY = Duration.ofMillis(250);
    private static final Duration RAMP_MAX_DURATION = Duration.ofSeconds(60);
    private static final double RAMP_FAIL_RATE_LIMIT = 0.05;

    private final AtomicBoolean isAnyTestRunning = new AtomicBoolean(false);

    @Override
    public Mono<TestRun> run(Long testId) {
        if (!isAnyTestRunning.compareAndSet(false, true)) {
            return Mono.error(new IllegalStateException("Some other test is already running."));
        }
        return Mono.fromCallable(() -> prepareRunInTx(testId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(prep -> {
                    hwSampleService.start(prep.run().getId(), prep.actuatorBase(), interval);

                    if (prep.run().getTestScenario() == TestScenario.RAMP_UP) {
                        return executeRampUp(prep.run(), prep.reqSnapshot())
                                .flatMap(ramp -> {
                                    prep.run().setConcurrency(ramp.finalConcurrency());
                                    prep.run().setTotalRequests(ramp.executedRequests());
                                    return finishSuccess(prep.run(), ramp.summary());
                                })
                                .onErrorResume(err -> finishFailure(prep.run(), err));
                    } else {
                      Integer totalRequests = prep.run().getTotalRequests();
                      Integer concurrency = prep.run().getConcurrency();
                      if (totalRequests == null || concurrency == null) {
                          return finishFailure(prep.run(),
                                  new IllegalStateException("STEADY scenari requires totalRequests and concurrency"));
                      }
                        return executeAndSummarize(prep.run(), prep.reqSnapshot())
                                .flatMap(summary ->{
                                    if (prep.run().getTotalRequests() == null) prep.run().setTotalRequests(totalRequests);
                                    if (prep.run().getConcurrency() == null) prep.run().setConcurrency(concurrency);
                                    return finishSuccess(prep.run(), summary);
                                })
                                .onErrorResume(err -> finishFailure(prep.run(), err));
                    }
                }).doFinally(signalType ->
                    isAnyTestRunning.set(false)
                );
    }

    private PreparedRun prepareRunInTx(Long testId) {

        return txTemplate.execute(status -> {
            TestRun entity = testRepository.findById(testId)
                    .orElseThrow(() -> new NoSuchElementException("TestRun not found: " + testId));

            if (entity.getStatus() != TestStatus.APPROVED && entity.getStatus() != TestStatus.WAITING) {
                throw new IllegalStateException("Test is not in APPROVED/WAITING state.");
            }

            String effective = composeTargetUrl(entity);
            entity.setEffectiveUrl(effective);
            entity.setStatus(TestStatus.RUNNING);
            entity.setStartedAt(Instant.now());

            RequestDefinition reqSrc = entity.getRequest();
            Map<String, String> headersCopy = reqSrc.getHeaders() == null
                    ? new HashMap<>()
                    : new HashMap<>(reqSrc.getHeaders());

            RequestDefinition reqSnapshot = RequestDefinition.builder()
                    .url(effective)
                    .method(reqSrc.getMethod())
                    .headers(headersCopy)
                    .body(reqSrc.getBody())
                    .contentType(reqSrc.getContentType())
                    .build();

            URI actuatorBase = buildActuatorBaseFromUrl(reqSrc.getUrl());
            TestRun saved = testRepository.save(entity);
            return new PreparedRun(saved, reqSnapshot, actuatorBase);
        });
    }

    private URI buildActuatorBaseFromUrl(String targetUrl) {
        try {
            URI target = URI.create(targetUrl);
            StringBuilder base = new StringBuilder()
                    .append(target.getScheme()).append("://").append(target.getHost());
            if (target.getPort() != -1) {
                base.append(":").append(target.getPort());
            }
            return URI.create(base.toString());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve actuator base from test URL: " + targetUrl, e);
        }
    }

    private Mono<RampResult> executeRampUp(TestRun run, RequestDefinition reqSnapshot) {
        final long testStartNs = System.nanoTime();

        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicInteger successes = new AtomicInteger(0);
        final AtomicInteger failures = new AtomicInteger(0);
        final AtomicInteger currentCap = new AtomicInteger(Math.max(1, RAMP_START_CONCURRENCY));
        final Semaphore sem = new Semaphore(currentCap.get(), true);

        Disposable deadline = Mono.delay(RAMP_MAX_DURATION)
                .doOnNext(t -> {
                    if (stop.compareAndSet(false, true)) {
                        log.info("RAMP-UP: stopping due to time limit {}", RAMP_MAX_DURATION);
                    }
                })
                .subscribe();

        Disposable ramp = Flux.interval(RAMP_STEP_EVERY)
                .takeUntil(t -> stop.get())
                .subscribe(t-> {
                    if(!stop.get()) {
                        int prev = currentCap.get();
                        int next = prev + RAMP_STEP;
                        int dif = next-prev;
                        if (dif > 0) {
                            currentCap.set(next);
                            sem.release(dif);
                            log.info("RAMP-UP: increased concurrency cap from {} to {}", prev, next);
                        }
                    }
                });

        return Flux.<Integer>generate(sink -> {
            if (stop.get()) {
                sink.complete();
                return; }
            sink.next(1);
        })
                .flatMap(token -> {
                    if (stop.get())
                        return Mono.empty();

                    try {
                        sem.acquire();
                    }
                    catch (InterruptedException e) {
                        return Mono.error(e);
                    }

                    final long t0 = System.nanoTime();
                    return apiRequestService.send(reqSnapshot)
                            .map((ResponseEntity<String> res) -> {
                                long clientMs = (System.nanoTime()-t0)/1_000_000;
                                int status = res.getStatusCode().value();
                                Long serverMs = null;
                                Long queueMs = null;
                                try {
                                    String body = res.getBody();
                                    if (body!= null &&!body.isBlank()) {
                                        JsonNode root = objectMapper.readTree(body);
                                        JsonNode nServer = root.get("serverProcessingMs");
                                        if (nServer != null && nServer.isNumber())
                                            serverMs = nServer.asLong();
                                        JsonNode nQueue = root.get("queueWaitMs");
                                        if (nQueue != null && nQueue.isNumber())
                                            queueMs = nQueue.asLong();
                                        if (serverMs == null) {
                                            JsonNode nDuration = root.get("durationMs");
                                            if (nDuration != null && nDuration.isNumber())
                                                serverMs = nDuration.asLong();
                                        }
                                    }
                                }
                                catch (Exception ignore) {}

                                boolean ok = status >= 200 && status < 300;
                                if (ok)
                                    successes.incrementAndGet();
                                else
                                    failures.incrementAndGet();

                                log.info("[RAMP] {} {} -> {} in {} ms (serverMs={}, queueMs={})",
                                        reqSnapshot.getMethod(), reqSnapshot.getUrl(), status, clientMs, serverMs, queueMs);

                                return new ExecutionResult(status, clientMs, serverMs, queueMs);
                            })
                            .onErrorResume(ex -> {
                                long clientMs = (System.nanoTime()-t0)/1_000_000;
                                log.error("[RAMP] ERROR {} in {} ms",
                                        ex.toString(), clientMs);
                                failures.incrementAndGet();
                                return Mono.just(new ExecutionResult(599, clientMs, null, null));
                            })
                            .doFinally(signalType -> sem.release())
                            .doOnSuccess(r-> {
                                int s = successes.get();
                                int f = failures.get();
                                int total = s + f;
                                double failRate = (total == 0) ? 0.0 : (double) f / (double) total;
                                if (!stop.get() && failRate >= RAMP_FAIL_RATE_LIMIT && f > 10 ) {
                                    if (stop.compareAndSet(false, true)) {
                                        log.info("RAMP-UP: stopping due to fail-rate limit: {} (fails={}/total={})",
                                                failRate, f, total);
                                    }
                                }
                                if (!stop.get() && cancels.isCancelRequested(run.getId())) {
                                    if (stop.compareAndSet(false, true)) {
                                        log.info("RAMP-UP: stopping due to user cancel");
                                    }
                                }
                            });
                },
                        Integer.MAX_VALUE
                )
                .takeUntil(__ -> stop.get())
                .collectList()
                .doFinally(signalType -> {
                    try {
                        if(deadline != null) deadline.dispose();
                    } catch (Throwable ignore) {}
                    try {
                        if (ramp != null) ramp.dispose();
                    } catch (Throwable ignore) {}
                })
                .map(results -> {
                    long wallClockMs = (System.nanoTime()- testStartNs)/1_000_000;
                    TestSummary summary = toSummary(results, wallClockMs);
                    int finalCap = currentCap.get();
                    int executed = results.size();
                    return new RampResult(summary, finalCap, executed);
                });

    }



    private Mono<TestRun> finishSuccess(TestRun running, TestSummary summary) {
        return Mono.fromCallable(() -> {
                    running.setSummary(summary);
                    running.setStatus(TestStatus.FINISHED);
                    running.setFinishedAt(Instant.now());
                    return testRepository.save(running);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(sig -> {
                    try {
                        hwSampleService.stopAndSummarize(running.getId());
                    } catch (Throwable t) {
                        log.warn("Failed to stop HW sampler for test {}: {}", running.getId(), t.toString());
                    }
                })
                .publishOn(Schedulers.boundedElastic());
    }

    private Mono<TestRun> finishFailure(TestRun running, Throwable err) {
        log.error("TestRun {} failed", running.getId(), err);
        return Mono.fromCallable(() -> {
                    TestSummary fallback = TestSummary.builder()
                            .successes(0)
                            .failures(Optional.ofNullable(running.getTotalRequests()).orElse(0))
                            .successRate(0.0)
                            .durationMs(running.getStartedAt() != null
                                    ? Math.max(0, java.time.Duration.between(running.getStartedAt(), Instant.now()).toMillis())
                                    : 0)
                            .throughputRps(0.0)
                            .avgResponseTimeMs(0.0)
                            .p95ResponseTimeMs(0.0)
                            .avgServerProcessingMs(null)
                            .p95ServerProcessingMs(null)
                            .avgQueueWaitMs(null)
                            .p95QueueWaitMs(null)
                            .build();

                    running.setSummary(fallback);
                    running.setStatus(TestStatus.FAILED);
                    running.setFinishedAt(Instant.now());
                    running.setErrorMessage(err.toString());
                    return testRepository.save(running);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(sig -> {
                    try {
                        hwSampleService.stopAndSummarize(running.getId());
                    } catch (Throwable t) {
                        log.warn("Failed to stop HW sampler for test {}: {}", running.getId(), t.toString());
                    }
                })
                .publishOn(Schedulers.boundedElastic());
    }

    @Override
    public Page<TestRun> search(TestStatus status, ProcessingMode mode, Instant from, Instant to, Pageable pageable) {
        Specification<TestRun> spec = (root, cq, cb) -> {
            var ps = new ArrayList<Predicate>();
            if (status != null) ps.add(cb.equal(root.get("status"), status));
            if (mode != null) ps.add(cb.equal(root.get("processingMode"), mode));
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) ps.add(cb.lessThan(root.get("createdAt"), to));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        return testRepository.findAll(spec, pageable);
    }

    private String composeTargetUrl(TestRun run) {
        var b = UriComponentsBuilder.fromUriString(run.getRequest().getUrl());
        if (run.getProcessingMode() != null)
            b.replaceQueryParam("mode", run.getProcessingMode().name());
        if (run.getPoolSizeOrCap() != null)
            b.replaceQueryParam("size", run.getPoolSizeOrCap());
        if (run.getDelayMs() != null)
            b.replaceQueryParam("delay", run.getDelayMs());
        return b.build(true).toUriString();
    }

    private Mono<TestSummary> executeAndSummarize(TestRun run, RequestDefinition reqSnapshot) {
        final int totalRequests = run.getTotalRequests();
        final int concurrency = Math.max(1, run.getConcurrency());
        final long testStartNs = System.nanoTime();
        final String effectiveUrl = reqSnapshot.getUrl();

        return Flux.range(1, totalRequests)
                .flatMap(i -> {
                    final long t0 = System.nanoTime();
                    if (cancels.isCancelRequested(run.getId())) {
                        return Mono.error(new CancellationException("Canceled by user"));
                    }
                    return apiRequestService.send(reqSnapshot)
                            .map((ResponseEntity<String> resp) -> {
                                long clientMs = (System.nanoTime() - t0) / 1_000_000;
                                int status = resp.getStatusCodeValue();
                                Long serverMs = null, queueMs = null;
                                try {
                                    String body = resp.getBody();
                                    if (body != null && !body.isBlank()) {
                                        JsonNode root = objectMapper.readTree(body);
                                        JsonNode nServer = root.get("serverProcessingMs");
                                        if (nServer != null && nServer.isNumber()) {
                                            serverMs = nServer.asLong();
                                        }
                                        JsonNode nQueue = root.get("queueWaitMs");
                                        if (nQueue != null && nQueue.isNumber()) {
                                            queueMs = nQueue.asLong();
                                        }
                                        if (serverMs == null) {
                                            JsonNode nDuration = root.get("durationMs");
                                            if (nDuration != null && nDuration.isNumber()) {
                                                serverMs = nDuration.asLong();
                                            }
                                        }
                                    }
                                } catch (Exception ignore) {}
                                log.info("[{}/{}] {} {} -> {} in {} ms (serverMs={}, queueMs={})",
                                        i, totalRequests, reqSnapshot.getMethod(), effectiveUrl, status, clientMs, serverMs, queueMs);
                                return new ExecutionResult(status, clientMs, serverMs, queueMs);
                            })
                            .onErrorResume(ex -> {
                                long clientMs = (System.nanoTime() - t0) / 1_000_000;
                                log.error("[{}/{}] ERROR {} in {} ms", i, totalRequests, ex.toString(), clientMs);
                                return Mono.just(new ExecutionResult(599, clientMs, null, null));
                            });
                }, concurrency)
                .collectList()
                .map(results -> {
                    long wallClockMs = (System.nanoTime() - testStartNs) / 1_000_000;
                    return toSummary(results, wallClockMs);
                });
    }

    private TestSummary toSummary(List<ExecutionResult> results, long wallClockMs) {
        int totalRequests = results.size();
        int successes = (int) results.stream()
                .filter(r -> r.getStatus() >= 200 && r.getStatus() < 300).count();
        int failures = totalRequests - successes;
        double successRatePct = (totalRequests == 0) ? 0.0 : (successes * 100.0) / totalRequests;

        List<Long> clientDurations = results.stream()
                .map(ExecutionResult::getClientLatencyMs)
                .collect(Collectors.toList());
        double avgClient = clientDurations.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);
        double p95Client = percentile(clientDurations, 95);

        List<Long> serverTimes = results.stream().map(ExecutionResult::getServerProcessingMs).filter(Objects::nonNull).collect(Collectors.toList());
        Double avgServer = serverTimes.isEmpty() ? null
                : round2(serverTimes.stream().mapToLong(Long::longValue).average().orElse(0.0));
        Double p95Server = serverTimes.isEmpty() ? null
                : round2(percentile(serverTimes, 95));

        List<Long> queueTimes = results.stream()
                .map(ExecutionResult::getQueueWaitMs)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Double avgQueue = queueTimes.isEmpty() ? null
                : round2(queueTimes.stream().mapToLong(Long::longValue).average().orElse(0.0));
        Double p95Queue = queueTimes.isEmpty() ? null
                : round2(percentile(queueTimes, 95));

        double throughputRps = wallClockMs > 0 ? totalRequests / (wallClockMs / 1000.0) : 0.0;

        return TestSummary.builder()
                .successes(successes)
                .failures(failures)
                .successRate(round2(successRatePct))
                .durationMs(wallClockMs)
                .throughputRps(round2(throughputRps))
                .avgResponseTimeMs(round2(avgClient))
                .p95ResponseTimeMs(round2(p95Client))
                .avgServerProcessingMs(avgServer)
                .p95ServerProcessingMs(p95Server)
                .avgQueueWaitMs(avgQueue)
                .p95QueueWaitMs(p95Queue)
                .build();
    }

    private double percentile(List<Long> values, double p) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        if (index < 0) index = 0;
        if (index >= sorted.size()) index = sorted.size() - 1;
        return sorted.get(index);
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private record RampResult(TestSummary summary, int finalConcurrency, int executedRequests){};
    private record PreparedRun(TestRun run, RequestDefinition reqSnapshot, URI actuatorBase) {}
}
