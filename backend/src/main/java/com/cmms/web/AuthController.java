package com.cmms.web;

import com.cmms.entity.User;
import com.cmms.repo.UserRepository;
import com.cmms.security.Current;
import com.cmms.security.JwtService;
import com.cmms.service.AuditService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record LoginIn(@NotBlank String username, @NotBlank String password) {}
    public record LoginOut(String token, User user) {}
    public record PasswordIn(@NotBlank String currentPassword, @NotBlank @Size(min = 8, message = "must be at least 8 characters") String newPassword) {}

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwt, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
    }

    @PostMapping("/login")
    public LoginOut login(@Valid @RequestBody LoginIn in) {
        User u = users.findByUsername(in.username().trim().toLowerCase())
                .filter(x -> x.active && encoder.matches(in.password(), x.passwordHash))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));
        return new LoginOut(jwt.issue(u), u);
    }

    @GetMapping("/me")
    public User me() {
        return users.findById(Current.user().id()).orElseThrow();
    }

    @PostMapping("/password")
    public void changePassword(@Valid @RequestBody PasswordIn in) {
        User u = users.findById(Current.user().id()).orElseThrow();
        if (!encoder.matches(in.currentPassword(), u.passwordHash)) throw ApiException.bad("Current password is incorrect");
        u.passwordHash = encoder.encode(in.newPassword());
        u.mustChangePassword = false;
        users.save(u);
        audit.log("user", u.id, "PASSWORD", null);
    }
}
