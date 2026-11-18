package com.cmms.security;

import com.cmms.repo.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtFilter extends OncePerRequestFilter {
    /** File downloads opened in a new tab can't send headers, so they carry a short-lived token in the URL. */
    static final Pattern DOWNLOAD_PATH = Pattern.compile("^/api/(assets/\\d+/documents/\\d+|reports/files/[A-Za-z0-9._-]+)$");
    /** What a user who must change their password may still call. */
    static final Set<String> PASSWORD_CHANGE_ALLOWED = Set.of("/api/auth/me", "/api/auth/password", "/api/auth/login");

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
        String path = req.getRequestURI();
        boolean fromQuery = false;
        String token = null;
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7);
        } else if (req.getParameter("access_token") != null && "GET".equals(req.getMethod()) && DOWNLOAD_PATH.matcher(path).matches()) {
            token = req.getParameter("access_token");
            fromQuery = true;
        }
        if (token != null) {
            try {
                Claims c = jwt.parse(token);
                boolean downloadToken = Boolean.TRUE.equals(c.get(JwtService.DOWNLOAD_CLAIM, Boolean.class));
                // URL tokens must be download tokens; download tokens are only valid in URLs
                if (downloadToken == fromQuery) {
                    Long id = Long.valueOf(c.getSubject());
                    Integer tv = c.get("tv", Integer.class);
                    users.findById(id)
                         .filter(u -> u.active && tv != null && tv == u.tokenVersion) // revoked after password change
                         .ifPresent(u -> {
                             if (u.mustChangePassword && !PASSWORD_CHANGE_ALLOWED.contains(path)) {
                                 req.setAttribute("cmms.passwordChangeRequired", true);
                                 return;
                             }
                             AuthUser principal = new AuthUser(u.id, u.username, u.role);
                             var auth = new UsernamePasswordAuthenticationToken(principal, null,
                                     List.of(new SimpleGrantedAuthority("ROLE_" + u.role.name())));
                             SecurityContextHolder.getContext().setAuthentication(auth);
                         });
                }
            } catch (Exception ignored) {
                // invalid/expired token -> request stays anonymous -> 401
            }
        }
        if (Boolean.TRUE.equals(req.getAttribute("cmms.passwordChangeRequired"))) {
            res.setStatus(403);
            res.setContentType("application/json");
            res.getWriter().write("{\"status\":403,\"error\":\"You must change your password before continuing\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
