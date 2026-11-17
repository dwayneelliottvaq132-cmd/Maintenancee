package com.cmms.config;

import com.cmms.entity.Enums.Role;
import com.cmms.entity.User;
import com.cmms.repo.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the first admin account on an empty database and makes sure data dirs exist. */
@Component
public class Bootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final CmmsProperties props;

    public Bootstrap(UserRepository users, PasswordEncoder encoder, CmmsProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Files.createDirectories(Path.of(props.uploadDir()));
        Files.createDirectories(Path.of(props.reportDir()));
        if (users.count() == 0) {
            User u = new User();
            u.username = "admin";
            u.fullName = "Administrator";
            u.role = Role.ADMIN;
            u.passwordHash = encoder.encode(props.adminPassword());
            u.mustChangePassword = true;
            users.save(u);
            log.warn("Created initial user 'admin' — change the password at first login");
        }
    }
}
