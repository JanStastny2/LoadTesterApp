package cz.uhk.loadtesterapp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.uhk.loadtesterapp.mapper.TestMapper;
import cz.uhk.loadtesterapp.model.dto.TestRunResponse;
import cz.uhk.loadtesterapp.model.dto.HwSampleDto;
import cz.uhk.loadtesterapp.model.entity.CancellationRegistry;
import cz.uhk.loadtesterapp.model.entity.TestRun;
import cz.uhk.loadtesterapp.model.enums.ProcessingMode;
import cz.uhk.loadtesterapp.model.enums.TestScenario;
import cz.uhk.loadtesterapp.model.enums.TestStatus;
import cz.uhk.loadtesterapp.service.TestCommandService;
import cz.uhk.loadtesterapp.service.TestRunQueryService;
import cz.uhk.loadtesterapp.service.TestRunnerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TestRunController.class)
@EnableMethodSecurity
class TestRunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TestRunnerService runnerService;

    @MockitoBean
    private TestRunQueryService testRunQueryService;

    @MockitoBean
    private TestCommandService commandService;

    @MockitoBean
    private CancellationRegistry cancellationRegistry;

    @MockitoBean
    private TestMapper testMapper;

    @Test
    @WithMockUser(username = "user", roles = "USER")
    void list_ShouldReturnPagedResults() throws Exception {
        when(testRunQueryService.search(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(testMapper.toResponse(any())).thenReturn(buildMockResponse());

        mockMvc.perform(get("/api/tests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void list_WithoutAuth_ShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/tests"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "user", roles = "USER")
    void detail_ShouldReturn200_WhenTestExists() throws Exception {
        TestRun run = buildTestRun(1L, TestStatus.FINISHED);
        when(testRunQueryService.findById(1L)).thenReturn(Optional.of(run));
        when(testMapper.toResponse(run)).thenReturn(buildMockResponse());

        mockMvc.perform(get("/api/tests/1"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "user", roles = "USER")
    void detail_ShouldReturn404_WhenTestNotFound() throws Exception {
        when(testRunQueryService.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/tests/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void approve_ShouldReturn200_WhenTestIsCreated() throws Exception {
        TestRun run = buildTestRun(1L, TestStatus.APPROVED);
        when(commandService.approve(1L)).thenReturn(Optional.of(run));
        when(testMapper.toResponse(run)).thenReturn(buildMockResponse());

        mockMvc.perform(post("/api/tests/1/approve").with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "user", roles = "USER")
    void approve_ShouldReturn403_ForNonAdmin() throws Exception {
        mockMvc.perform(post("/api/tests/1/approve").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void run_ShouldReturn202_WhenTestExists() throws Exception {
        TestRun run = buildTestRun(1L, TestStatus.APPROVED);
        when(testRunQueryService.findById(1L)).thenReturn(Optional.of(run));
        when(runnerService.run(1L)).thenReturn(Mono.just(run));

        mockMvc.perform(post("/api/tests/1/run").with(csrf()))
                .andExpect(status().isAccepted());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void run_ShouldReturn404_WhenTestNotFound() throws Exception {
        when(testRunQueryService.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/tests/99/run").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "user", roles = "USER")
    void cancel_ShouldReturn200_WhenTestIsWaiting() throws Exception {
        TestRun run = buildTestRun(1L, TestStatus.WAITING);
        when(testRunQueryService.findById(1L)).thenReturn(Optional.of(run));
        when(commandService.cancel(1L)).thenReturn(Optional.of(run));

        mockMvc.perform(post("/api/tests/1/cancel").with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "user", roles = "USER")
    void cancel_ShouldReturn202_WhenTestIsRunning() throws Exception {
        TestRun run = buildTestRun(1L, TestStatus.RUNNING);
        when(testRunQueryService.findById(1L)).thenReturn(Optional.of(run));
        doNothing().when(cancellationRegistry).requestCancel(1L);

        mockMvc.perform(post("/api/tests/1/cancel").with(csrf()))
                .andExpect(status().isAccepted());
    }

    @Test
    @WithMockUser(username = "user", roles = "USER")
    void cancel_ShouldReturn409_WhenTestIsFinished() throws Exception {
        TestRun run = buildTestRun(1L, TestStatus.FINISHED);
        when(testRunQueryService.findById(1L)).thenReturn(Optional.of(run));

        mockMvc.perform(post("/api/tests/1/cancel").with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(username = "user", roles = "USER")
    void cancel_ShouldReturn404_WhenTestNotFound() throws Exception {
        when(testRunQueryService.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/tests/99/cancel").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void delete_ShouldReturn200_WhenTestExists() throws Exception {
        when(commandService.deleteById(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/tests/1").with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void delete_ShouldReturn404_WhenTestNotFound() throws Exception {
        when(commandService.deleteById(99L)).thenReturn(false);

        mockMvc.perform(delete("/api/tests/99").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "user", roles = "USER")
    void create_ShouldReturn400_WhenSerialModeAndPoolSizeIsNot1() throws Exception {
        mockMvc.perform(post("/api/tests")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest(ProcessingMode.SERIAL, 2))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(commandService);
    }

    @Test
    @WithMockUser(username = "user", roles = "USER")
    void update_ShouldReturn400_WhenSerialModeAndPoolSizeIsNot1() throws Exception {
        mockMvc.perform(put("/api/tests/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildUpdateRequest(ProcessingMode.SERIAL, 2))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(commandService);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void reject_ShouldReturn200_WhenTestIsCreated() throws Exception {
        TestRun run = buildTestRun(1L, TestStatus.REJECTED);
        when(commandService.reject(1L)).thenReturn(Optional.of(run));
        when(testMapper.toResponse(run)).thenReturn(buildMockResponse());

        mockMvc.perform(post("/api/tests/1/reject").with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "user", roles = "USER")
    void reject_ShouldReturn403_ForNonAdmin() throws Exception {
        mockMvc.perform(post("/api/tests/1/reject").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "user", roles = "USER")
    void compare_ShouldReturnItems_ForRequestedIds() throws Exception {
        when(testRunQueryService.findAllById(anyList()))
                .thenReturn(java.util.List.of(buildTestRun(1L, TestStatus.FINISHED), buildTestRun(2L, TestStatus.FAILED)));

        mockMvc.perform(get("/api/tests/compare").param("ids", "1", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[1].id").value(2));
    }

    @Test
    @WithMockUser(username = "user", roles = "USER")
    void hwSeries_ShouldReturnSamples() throws Exception {
        when(testRunQueryService.getHwSamples(1L))
                .thenReturn(java.util.List.of(new HwSampleDto(Instant.now(), 12.5, 128.0)));

        mockMvc.perform(get("/api/tests/1/hw-samples"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cpu").value(12.5))
                .andExpect(jsonPath("$[0].heapMb").value(128.0));
    }

    private TestRun buildTestRun(Long id, TestStatus status) {
        return TestRun.builder()
                .id(id)
                .status(status)
                .testScenario(TestScenario.STEADY)
                .processingMode(ProcessingMode.SERIAL)
                .createdAt(Instant.now())
                .build();
    }

    private TestRunResponse buildMockResponse() {
        return new TestRunResponse(
                1L, TestScenario.STEADY, TestStatus.FINISHED,
                100, 10, ProcessingMode.SERIAL,
                1, null,
                null, null,
                null, null, null,
                Instant.now(), Instant.now(), Instant.now(),
                null
        );
    }

    private Object buildCreateRequest(ProcessingMode processingMode, int poolSizeOrCap) {
        return java.util.Map.of(
                "testScenario", "STEADY",
                "totalRequests", 10,
                "concurrency", 1,
                "processingMode", processingMode.name(),
                "poolSizeOrCap", poolSizeOrCap,
                "delayMs", 0,
                "request", java.util.Map.of(
                        "url", "http://localhost:8081/api/work/records",
                        "method", "GET",
                        "headers", java.util.Map.of(),
                        "body", "",
                        "contentType", "application/json"
                )
        );
    }

    private Object buildUpdateRequest(ProcessingMode processingMode, int poolSizeOrCap) {
        return buildCreateRequest(processingMode, poolSizeOrCap);
    }
}
