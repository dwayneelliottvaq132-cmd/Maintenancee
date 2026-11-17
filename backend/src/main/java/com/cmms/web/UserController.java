package com.cmms.web;

import com.cmms.entity.Enums.Role;
import com.cmms.entity.User;
import com.cmms.repo.UserRepository;
import com.cmms.security.Current;
import com.cmms.service.AuditService;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {
    public record UserIn(String username, String fullName, String email, Role role, BigDecimal hourlyRate,
                         String trade, Boolean active, String password) {}

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UserController(UserRepository users, PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
    }

    /** Everyone can read the employee list (names for assignments); hourly rates are hidden from non-supervisors. */
    @GetMapping
    public List<User> list() {
        List<User> all = users.findAll(Sort.by("fullName"));
        if (!Current.user().isSupervisor()) all.forEach(u -> u.hourlyRate = null);
        return all;
    }

    @PostMapping
    @PreAuthorize(Roles.ADMIN)
    public User create(@RequestBody UserIn in) {
        if (in.username() == null || in.username().isBlank()) throw ApiException.bad("Username is required");
        String uname = in.username().trim().toLowerCase();
        if (users.existsByUsername(uname)) throw ApiException.conflict("Username already exists");
        if (in.password() == null || in.password().length() < 8) throw ApiException.bad("Password must be at least 8 characters");
        User u = new User();
        u.username = uname;
        apply(u, in);
        u.passwordHash = encoder.encode(in.password());
        u.mustChangePassword = true;
        u = users.save(u);
        audit.log("user", u.id, "CREATE", u.username);
        return u;
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.ADMIN)
    public User update(@PathVariable Long id, @RequestBody UserIn in) {
        User u = users.findById(id).orElseThrow(() -> ApiException.notFound("User", id));
        if (u.id.equals(Current.user().id()) && in.role() != null && in.role() != Role.ADMIN)
            throw ApiException.bad("You cannot remove your own admin role");
        apply(u, in);
        if (in.password() != null && !in.password().isBlank()) {
            if (in.password().length() < 8) throw ApiException.bad("Password must be at least 8 characters");
            u.passwordHash = encoder.encode(in.password());
            u.mustChangePassword = true;
        }
        audit.log("user", u.id, "UPDATE", u.username);
        return users.save(u);
    }

    private void apply(User u, UserIn in) {
        if (in.fullName() == null || in.fullName().isBlank()) throw ApiException.bad("Full name is required");
        u.fullName = in.fullName();
        u.email = in.email();
        u.role = in.role() == null ? Role.TECHNICIAN : in.role();
        u.hourlyRate = in.hourlyRate() == null ? BigDecimal.ZERO : in.hourlyRate();
        u.trade = in.trade();
        if (in.active() != null) u.active = in.active();
    }
}
