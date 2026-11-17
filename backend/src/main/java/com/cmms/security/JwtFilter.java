package com.cmms.security;

import com.cmms.entity.Enums.Role;
import com.cmms.repo.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final UserRepository users;

    public JwtFilter(JwtService jwt, UserRepository users) {
        this.jwt = jwt;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        String token = null;
        if (header != null && header.startsWith("Bearer ")) token = header.substring(7);
        else if (req.getParameter("access_token") != null) token = req.getParameter("access_token"); // for file downloads
        if (token != null) {
            try {
                Claims c = jwt.parse(token);
                Long id = Long.valueOf(c.getSubject());
                // re-check that the user still exists and is active
                users.findById(id).filter(u -> u.active).ifPresent(u -> {
                    AuthUser principal = new AuthUser(u.id, u.username, u.role);
                    var auth = new UsernamePasswordAuthenticationToken(principal, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + u.role.name())));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
            } catch (Exception ignored) {
                // invalid/expired token -> request stays anonymous -> 401
            }
        }
        chain.doFilter(req, res);
    }

    static String roleAuthority(Role r) { return "ROLE_" + r.name(); }
}
