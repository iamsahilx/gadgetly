package com.gadgetly.controller;

import com.gadgetly.entity.User;
import com.gadgetly.exception.ApiException;
import com.gadgetly.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class UserController {

    private final UserRepository userRepository;

    @GetMapping("/me")
    public ResponseEntity<User> me() {
        User user = userRepository.findById(CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("User not found"));
        user.setPasswordHash(null); // never leak the hash
        return ResponseEntity.ok(user);
    }
}
