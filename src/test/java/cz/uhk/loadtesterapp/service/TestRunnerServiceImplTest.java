package cz.uhk.loadtesterapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.uhk.loadtesterapp.model.entity.CancellationRegistry;
import cz.uhk.loadtesterapp.model.entity.RequestDefinition;
import cz.uhk.loadtesterapp.model.entity.TestRun;
import cz.uhk.loadtesterapp.model.enums.HttpMethodType;
import cz.uhk.loadtesterapp.model.enums.ProcessingMode;
import cz.uhk.loadtesterapp.model.enums.TestScenario;
import cz.uhk.loadtesterapp.model.enums.TestStatus;
import cz.uhk.loadtesterapp.repository.TestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class TestRunnerServiceImplTest {

    @Mock
    private TestRepository testRepository;

    @Mock
    private ApiRequestService apiRequestService;

    @Mock
    private CancellationRegistry cancels;

    @Mock
    private HwSampleService hwSampleService;

    @Mock
    private TransactionTemplate txTemplate;

    @InjectMocks
    private TestRunnerServiceImpl testRunnerService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        try {
            var f = TestRunnerServiceImpl.class.getDeclaredField("objectMapper");
            f.setAccessible(true);
            f.set(testRunnerService, objectMapper);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject ObjectMapper", e);
        }
    }

    @Test
    void run_ShouldReturnError_WhenTestNotFound() {
        when(txTemplate.execute(any())).thenThrow(new NoSuchElementException("TestRun not found: 1"));

        Mono<TestRun> result = testRunnerService.run(1L);

        StepVerifier.create(result)
                .expectErrorMatches(ex -> ex instanceof NoSuchElementException)
                .verify();
    }

    @Test
    void run_ShouldReturnError_WhenCalledTwiceConcurrently() {
        when(txTemplate.execute(any())).thenAnswer(inv -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return null;
        });

        testRunnerService.run(99L).subscribe();
        Mono<TestRun> secondCall = testRunnerService.run(99L);
        StepVerifier.create(secondCall)
                .expectErrorMatches(ex -> ex instanceof IllegalStateException
                        && ex.getMessage().contains("already running"))
                .verify();
    }

    @Test
    void run_ShouldRejectSteadyScenario_WhenTotalRequestsIsNull() {
        TestRun testRun = TestRun.builder()
                .id(1L)
                .testScenario(TestScenario.STEADY)
                .processingMode(ProcessingMode.SERIAL)
                .status(TestStatus.APPROVED)
                .totalRequests(null)
                .concurrency(null)
                .request(RequestDefinition.builder()
                        .url("http://localhost:8081/api/work/records")
                        .method(HttpMethodType.GET)
                        .build())
                .build();

        when(txTemplate.execute(any())).thenAnswer(inv -> {
            var cb = (org.springframework.transaction.support.TransactionCallback<?>) inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        when(testRepository.findById(1L)).thenReturn(Optional.of(testRun));
        when(testRepository.save(any(TestRun.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(hwSampleService.start(any(), any(), any())).thenReturn(null);

        Mono<TestRun> result = testRunnerService.run(1L);

        StepVerifier.create(result)
                .assertNext(saved -> assertEquals(TestStatus.FAILED, saved.getStatus()))
                .verifyComplete();
    }

    @Test
    void run_ShouldAppendModeSizeAndDelay_ToEffectiveUrl() {
        TestRun testRun = TestRun.builder()
                .id(1L)
                .testScenario(TestScenario.STEADY)
                .processingMode(ProcessingMode.POOL)
                .status(TestStatus.APPROVED)
                .totalRequests(1)
                .concurrency(1)
                .poolSizeOrCap(4)
                .delayMs(50L)
                .request(RequestDefinition.builder()
                        .url("http://localhost:8081/api/work/records")
                        .method(HttpMethodType.GET)
                        .build())
                .build();

        when(txTemplate.execute(any())).thenAnswer(inv -> {
            var cb = (org.springframework.transaction.support.TransactionCallback<?>) inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        when(testRepository.findById(1L)).thenReturn(Optional.of(testRun));
        when(testRepository.save(any(TestRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(hwSampleService.start(eq(1L), any(URI.class), any(Duration.class))).thenReturn(mock(reactor.core.Disposable.class));
        doNothing().when(hwSampleService).stopAndSummarize(1L);
        when(apiRequestService.send(any(RequestDefinition.class)))
                .thenReturn(Mono.just(ResponseEntity.ok("{" +
                        "\"serverProcessingMs\":10," +
                        "\"queueWaitMs\":2}")));

        StepVerifier.create(testRunnerService.run(1L))
                .assertNext(saved -> {
                    assertEquals(TestStatus.FINISHED, saved.getStatus());
                    assertEquals("http://localhost:8081/api/work/records?mode=POOL&size=4&delayMs=50", saved.getEffectiveUrl());
                    assertNotNull(saved.getSummary());
                })
                .verifyComplete();
    }

    @Test
    void run_ShouldReturnError_WhenStatusIsNotApprovedOrWaiting() {
        TestRun testRun = TestRun.builder()
                .id(1L)
                .testScenario(TestScenario.STEADY)
                .processingMode(ProcessingMode.SERIAL)
                .status(TestStatus.CREATED)
                .totalRequests(1)
                .concurrency(1)
                .request(RequestDefinition.builder()
                        .url("http://localhost:8081/api/work/records")
                        .method(HttpMethodType.GET)
                        .build())
                .build();

        when(txTemplate.execute(any())).thenAnswer(inv -> {
            var cb = (org.springframework.transaction.support.TransactionCallback<?>) inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        when(testRepository.findById(1L)).thenReturn(Optional.of(testRun));

        StepVerifier.create(testRunnerService.run(1L))
                .expectErrorMatches(ex -> ex instanceof IllegalStateException
                        && ex.getMessage().contains("APPROVED/WAITING"))
                .verify();
    }

    @Test
    void run_ShouldFinishWithFailedSummary_WhenApiCallErrors() {
        TestRun testRun = TestRun.builder()
                .id(1L)
                .testScenario(TestScenario.STEADY)
                .processingMode(ProcessingMode.SERIAL)
                .status(TestStatus.APPROVED)
                .totalRequests(1)
                .concurrency(1)
                .request(RequestDefinition.builder()
                        .url("http://localhost:8081/api/work/records")
                        .method(HttpMethodType.GET)
                        .build())
                .build();

        when(txTemplate.execute(any())).thenAnswer(inv -> {
            var cb = (org.springframework.transaction.support.TransactionCallback<?>) inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        when(testRepository.findById(1L)).thenReturn(Optional.of(testRun));
        when(testRepository.save(any(TestRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(hwSampleService.start(eq(1L), any(URI.class), any(Duration.class))).thenReturn(mock(reactor.core.Disposable.class));
        doNothing().when(hwSampleService).stopAndSummarize(1L);
        when(apiRequestService.send(any(RequestDefinition.class))).thenReturn(Mono.error(new RuntimeException("boom")));

        StepVerifier.create(testRunnerService.run(1L))
                .assertNext(saved -> {
                    assertEquals(TestStatus.FINISHED, saved.getStatus());
                    assertEquals(0, saved.getSummary().getSuccesses());
                    assertEquals(1, saved.getSummary().getFailures());
                    assertEquals(0.0, saved.getSummary().getSuccessRate());
                })
                .verifyComplete();
    }
}
