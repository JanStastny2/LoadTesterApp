package cz.uhk.loadtesterapp.service;

import cz.uhk.loadtesterapp.mapper.TestMapper;
import cz.uhk.loadtesterapp.model.dto.TestUpdateRequest;
import cz.uhk.loadtesterapp.model.entity.RequestDefinition;
import cz.uhk.loadtesterapp.model.entity.TestRun;
import cz.uhk.loadtesterapp.model.entity.User;
import cz.uhk.loadtesterapp.model.enums.HttpMethodType;
import cz.uhk.loadtesterapp.model.enums.ProcessingMode;
import cz.uhk.loadtesterapp.model.enums.Role;
import cz.uhk.loadtesterapp.model.enums.TestScenario;
import cz.uhk.loadtesterapp.model.enums.TestStatus;
import cz.uhk.loadtesterapp.repository.TestRepository;
import cz.uhk.loadtesterapp.security.MyUserDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestCommandServiceImplTest {

    @Mock
    private TestRepository repo;

    @Mock
    private UserService userService;

    @Mock
    private TestMapper testMapper;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private TestCommandServiceImpl service;

    @Test
    void create_ShouldSetCreatedByStatusAndCreatedAt() {
        User user = buildUser(1L, "john");
        TestRun testRun = TestRun.builder()
                .testScenario(TestScenario.STEADY)
                .processingMode(ProcessingMode.SERIAL)
                .request(buildRequest("http://localhost/api/test"))
                .build();

        when(authentication.getPrincipal()).thenReturn(new MyUserDetails(user));
        when(userService.getUserById(1L)).thenReturn(Optional.of(user));
        when(repo.save(any(TestRun.class))).thenAnswer(inv -> inv.getArgument(0));

        TestRun saved = service.create(testRun, authentication);

        assertEquals(TestStatus.CREATED, saved.getStatus());
        assertEquals(user, saved.getCreatedBy());
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void update_ShouldThrow_WhenStatusIsNotCreated() {
        TestRun existing = TestRun.builder()
                .id(1L)
                .status(TestStatus.APPROVED)
                .testScenario(TestScenario.STEADY)
                .processingMode(ProcessingMode.SERIAL)
                .request(buildRequest("http://localhost/api/test"))
                .build();

        when(repo.findById(1L)).thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class, () -> service.update(1L, buildUpdateRequest()));
    }

    @Test
    void approve_ShouldThrow_WhenStatusIsNotCreated() {
        TestRun existing = TestRun.builder()
                .id(1L)
                .status(TestStatus.APPROVED)
                .testScenario(TestScenario.STEADY)
                .processingMode(ProcessingMode.SERIAL)
                .request(buildRequest("http://localhost/api/test"))
                .build();

        when(repo.findById(1L)).thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class, () -> service.approve(1L));
    }

    @Test
    void cancel_ShouldSetCancelledStatus_AndFinishedAt_WhenWaiting() {
        TestRun existing = TestRun.builder()
                .id(1L)
                .status(TestStatus.WAITING)
                .testScenario(TestScenario.STEADY)
                .processingMode(ProcessingMode.SERIAL)
                .request(buildRequest("http://localhost/api/test"))
                .build();

        when(repo.findById(1L)).thenReturn(Optional.of(existing));

        TestRun canceled = service.cancel(1L).orElseThrow();

        assertEquals(TestStatus.CANCELLED, canceled.getStatus());
        assertNotNull(canceled.getFinishedAt());
    }

    @Test
    void update_ShouldMapRequestAndMutableFields_WhenCreated() {
        TestRun existing = TestRun.builder()
                .id(1L)
                .status(TestStatus.CREATED)
                .testScenario(TestScenario.STEADY)
                .processingMode(ProcessingMode.SERIAL)
                .request(buildRequest("http://localhost/api/test"))
                .build();
        RequestDefinition mapped = buildRequest("http://localhost/api/updated");

        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(testMapper.toEntity(buildUpdateRequest().request())).thenReturn(mapped);

        TestRun updated = service.update(1L, buildUpdateRequest()).orElseThrow();

        assertEquals(20, updated.getTotalRequests());
        assertEquals(3, updated.getConcurrency());
        assertEquals(ProcessingMode.POOL, updated.getProcessingMode());
        assertEquals(4, updated.getPoolSizeOrCap());
        assertEquals(50L, updated.getDelayMs());
        assertEquals(mapped, updated.getRequest());
        verify(testMapper).toEntity(buildUpdateRequest().request());
    }

    private TestUpdateRequest buildUpdateRequest() {
        return new TestUpdateRequest(
                20,
                TestScenario.STEADY,
                3,
                ProcessingMode.POOL,
                4,
                new cz.uhk.loadtesterapp.model.dto.RequestDefinitionDto(
                        "http://localhost/api/updated",
                        HttpMethodType.POST,
                        Map.of("X-Test", "1"),
                        "{}",
                        "application/json"
                ),
                50L
        );
    }

    private RequestDefinition buildRequest(String url) {
        return RequestDefinition.builder()
                .url(url)
                .method(HttpMethodType.GET)
                .build();
    }

    private User buildUser(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("secret");
        user.setEmail(username + "@example.com");
        user.setRole(Role.USER);
        return user;
    }
}
