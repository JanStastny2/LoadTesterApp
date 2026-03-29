package cz.uhk.loadtesterapp.controller;

import cz.uhk.loadtesterapp.mapper.UserMapper;
import cz.uhk.loadtesterapp.model.dto.*;
import cz.uhk.loadtesterapp.model.entity.User;
import cz.uhk.loadtesterapp.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<List<UserDto>> getAll() {
        var users = userService.getAllUsers();
        return ResponseEntity.ok(users.stream()
                .map(userMapper::toUserDto).toList());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getUser(@PathVariable Long id) {
        return userService.getUserById(id)
                .map(userMapper::toUserDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<UserDto> create(@RequestBody @Valid UserCreateRequest req, UriComponentsBuilder uriBuilder) {
        var saved = userService.saveUser(userMapper.toEntity(req));
        var dto = userMapper.toUserDto(saved);
        var location = uriBuilder.buildAndExpand(dto.id()).toUri();
        return ResponseEntity.created(location).body(dto);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/username/{username}")
    public ResponseEntity<User> getUserByUsername(@PathVariable String username) {
        User user = userService.findByUsername(username);
        if (user != null) {
            return ResponseEntity.ok(user);
        }
        return ResponseEntity.notFound().build();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return userService.getUserById(id)
                .<ResponseEntity<?>>map(user -> {
                    userService.deleteUser(id);
                    return new ResponseEntity<>(HttpStatus.OK);
                })
                .orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ResponseEntity<UserDto> update(@PathVariable Long id, @RequestBody UserUpdateRequest req) {
        User entity = userService.getUserById(id)
                .orElse(null);
        if (entity == null)
            return ResponseEntity.notFound().build();

        userMapper.updateEntity(req, entity);
        var saved = userService.updateUser(entity, id);
        return ResponseEntity.ok(userMapper.toUserDto(saved));
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> me(@AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails principal) {
        if (principal == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        var user = userService.findByUsername(principal.getUsername());
        if (user == null)
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok(userMapper.toUserDto(user));
    }

    @PatchMapping("/me/password")
    public ResponseEntity<Void> changeMyPassword(@AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails principal,
                                                 @RequestBody @Valid ChangePasswordRequest body) {
        if (principal == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        var user = userService.findByUsername(principal.getUsername());
        if (user == null)
            return ResponseEntity.notFound().build();
        userService.changePassword(user.getId(), body.oldPassword(), body.newPassword());
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/password")
    public ResponseEntity<Void> adminSetPassword(@RequestBody AdminSetPasswordRequest body, @PathVariable Long id) {
        userService.adminSetPassword(id, body.newPassword());
        return ResponseEntity.ok().build();
    }


}
