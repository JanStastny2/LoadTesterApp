package cz.uhk.loadtesterapp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.uhk.loadtesterapp.mapper.UserMapper;
import cz.uhk.loadtesterapp.model.dto.UserDto;
import cz.uhk.loadtesterapp.model.entity.User;
import cz.uhk.loadtesterapp.model.enums.Role;
import cz.uhk.loadtesterapp.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@EnableMethodSecurity
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserMapper userMapper;

    @Test
    void me_ShouldReturn401_WhenPrincipalMissing() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changeMyPassword_ShouldReturn401_WhenPrincipalMissing() throws Exception {
        mockMvc.perform(patch("/api/users/me/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "oldPassword", "old-secret",
                                "newPassword", "new-secret"
                        ))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void create_ShouldReturn201_AndLocationHeader() throws Exception {
        User entity = buildUser(1L, "john");
        UserDto dto = new UserDto(1L, "john", "john@example.com", Role.USER);

        when(userMapper.toEntity(any())).thenReturn(entity);
        when(userService.saveUser(entity)).thenReturn(entity);
        when(userMapper.toUserDto(entity)).thenReturn(dto);

        mockMvc.perform(post("/api/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", "john",
                                "password", "secret",
                                "email", "john@example.com",
                                "role", "USER"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.username").value("john"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminSetPassword_ShouldReturn200_ForAdmin() throws Exception {
        doNothing().when(userService).adminSetPassword(1L, "new-secret");

        mockMvc.perform(patch("/api/users/1/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("newPassword", "new-secret"))))
                .andExpect(status().isOk());

        verify(userService).adminSetPassword(1L, "new-secret");
    }

    @Test
    @WithMockUser(username = "john", roles = "USER")
    void me_ShouldReturnUserDto_WhenPrincipalExists() throws Exception {
        User user = buildUser(1L, "john");
        UserDto dto = new UserDto(1L, "john", "john@example.com", Role.USER);

        when(userService.findByUsername("john")).thenReturn(user);
        when(userMapper.toUserDto(user)).thenReturn(dto);

        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.username").value("john"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void getUser_ShouldReturn404_WhenMissing() throws Exception {
        when(userService.getUserById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/users/99"))
                .andExpect(status().isNotFound());
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
