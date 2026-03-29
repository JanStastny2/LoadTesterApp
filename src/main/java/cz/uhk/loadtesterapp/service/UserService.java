package cz.uhk.loadtesterapp.service;


import cz.uhk.loadtesterapp.model.entity.User;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.List;
import java.util.Optional;

public interface UserService extends UserDetailsService {

    List<User> getAllUsers();
    User saveUser(User user);
    Optional<User> getUserById(Long id);
    User findByUsername(String username);
    User updateUser(User user, Long id);
    void deleteUser(Long id);
    void adminSetPassword(Long id, String newPassword);
    void changePassword(Long id, String oldPassword, String newPassword);
}
