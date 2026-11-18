package com.cmms.web;

import com.cmms.entity.Enums.Role;
import com.cmms.entity.User;
import com.cmms.repo.UserRepository;
import com.cmms.security.AuthUser;
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
    /** All fields optional on update: a missing field keeps its current value. */
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

    /**
     * Everyone can read names (for assignments and request lists). Hourly rates are only shown to
     * supervisors; requesters only get names and roles.
     */
    @GetMapping
    public List<User> list() {
        AuthUser me = Current.user();
        List<User> all = users.findAll(Sort.by("fullName"));
        if (!me.isSupervisor()) all.forEach(u -> u.hourlyRate = null);
        if (me.isRequesterOnly()) all.forEach(u -> { u.email = null; u.trade = null; });
        return all;
    }

    @PostMapping
    @PreAuthorize(Roles.ADMIN)
    public User create(@RequestBody UserIn in) {
        if (in.username() == null || in.username().isBlank()) throw ApiException.bad("Username is required");
        String uname = in.username().trim().toLowerCase();
        if (!uname.matches("[a-z0-9._-]{2,60}")) throw ApiException.bad("Username may only use letters, numbers, dot, dash and underscore");
        if (users.existsByUsername(uname)) throw ApiException.conflict("Username already exists");
        if (in.password() == null || in.password().length() < 8) throw ApiException.bad("Password must be at least 8 characters");
        if (in.fullName() == null || in.fullName().isBlank()) throw ApiException.bad("Full name is required");
        User u = new User();
        u.username = uname;
        u.fullName = in.fullName().trim();
        u.email = blankToNull(in.email());
        u.role = in.role() == null ? Role.TECHNICIAN : in.role();
        u.hourlyRate = checkRate(in.hourlyRate() == null ? BigDecimal.ZERO : in.hourlyRate());
        u.trade = blankToNull(in.trade());
        u.active = in.active() == null || in.active();
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
        boolean self = u.id.equals(Current.user().id());
        if (in.fullName() != null) {
            if (in.fullName().isBlank()) throw ApiException.bad("Full name is required");
            u.fullName = in.fullName().trim();
        }
        if (in.email() != null) u.email = blankToNull(in.email());
        if (in.trade() != null) u.trade = blankToNull(in.trade());
        if (in.hourlyRate() != null) u.hourlyRate = checkRate(in.hourlyRate());
        if (in.role() != null) {
            if (self && in.role() != Role.ADMIN) throw ApiException.bad("You cannot remove your own admin role");
            u.role = in.role();
        }
        boolean revoke = false;
        if (in.active() != null && in.active() != u.active) {
            if (self && !in.active()) throw ApiException.bad("You cannot deactivate your own account");
            u.active = in.active();
            revoke = !u.active;
        }
        if (in.password() != null && !in.password().isBlank()) {
            if (in.password().length() < 8) throw ApiException.bad("Password must be at least 8 characters");
            u.passwordHash = encoder.encode(in.password());
            u.mustChangePassword = true;
            revoke = true;
        }
        if (revoke) u.tokenVersion++;   // sign the user out of every open session
        audit.log("user", u.id, "UPDATE", u.username);
        return users.save(u);
    }

    private static BigDecimal checkRate(BigDecimal rate) {
        if (rate.signum() < 0) throw ApiException.bad("Hourly rate cannot be negative");
        return rate;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
