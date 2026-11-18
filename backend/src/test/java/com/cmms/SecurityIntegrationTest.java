package com.cmms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cmms.entity.Asset;
import com.cmms.entity.Enums.Role;
import com.cmms.entity.User;
import com.cmms.repo.AssetRepository;
import com.cmms.repo.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP-level checks for the access-control and session findings from the code review. */
@SpringBootTest(properties = {
        "spring.datasource.url=${CMMS_TEST_DB_URL:jdbc:postgresql://localhost:5432/cmms_test}",
        "cmms.upload-dir=target/test-data/uploads", "cmms.report-dir=target/test-data/reports"})
@AutoConfigureMockMvc
class SecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AssetRepository assets;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;

    String sfx;
    static final String PW = "Passw0rd!x";

    User user(String name, Role role, boolean mustChange) {
        User u = new User();
        u.username = name + sfx;
        u.fullName = name + " " + sfx;
        u.role = role;
        u.email = name + "@example.com";
        u.hourlyRate = BigDecimal.valueOf(40);
        u.passwordHash = encoder.encode(PW);
        u.mustChangePassword = mustChange;
        return users.save(u);
    }

    String login(User u) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + u.username + "\",\"password\":\"" + PW + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    @BeforeEach
    void setUp() {
        sfx = UUID.randomUUID().toString().substring(0, 6);
    }

    @Test
    void requesterCannotReachStaffData() throws Exception {
        String t = login(user("req", Role.REQUESTER, false));
        mvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + t)).andExpect(status().isForbidden());
        mvc.perform(get("/api/parts").header("Authorization", "Bearer " + t)).andExpect(status().isForbidden());
        mvc.perform(get("/api/pm-schedules").header("Authorization", "Bearer " + t)).andExpect(status().isForbidden());
        mvc.perform(get("/api/work-orders").header("Authorization", "Bearer " + t)).andExpect(status().isForbidden());
        // the asset list is allowed (needed to report a problem) but without costs
        String list = mvc.perform(get("/api/assets").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("lifetimeCost");
        // colleagues' emails and pay are hidden
        String people = mvc.perform(get("/api/users").header("Authorization", "Bearer " + t))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode n : json.readTree(people)) {
            assertThat(n.get("email").isNull()).isTrue();
            assertThat(n.get("hourlyRate").isNull()).isTrue();
        }
    }

    @Test
    void technicianSeesNoSpendingOrRates() throws Exception {
        String t = login(user("tech", Role.TECHNICIAN, false));
        String dash = mvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(dash).doesNotContain("spendThisMonth").doesNotContain("monthlySpend");
        mvc.perform(get("/api/reports/spending?from=2026-01-01&to=2026-12-31").header("Authorization", "Bearer " + t))
                .andExpect(status().isForbidden());
    }

    @Test
    void passwordChangeSignsOutOldSessions() throws Exception {
        User u = user("tech", Role.TECHNICIAN, false);
        String old = login(u);
        String body = mvc.perform(post("/api/auth/password").header("Authorization", "Bearer " + old)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"N3w-password!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String fresh = json.readTree(body).get("token").asText();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + old)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + fresh)).andExpect(status().isOk());
    }

    @Test
    void mustChangePasswordIsEnforcedByTheServer() throws Exception {
        String t = login(user("new", Role.SUPERVISOR, true));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + t)).andExpect(status().isOk());
        mvc.perform(get("/api/work-orders").header("Authorization", "Bearer " + t)).andExpect(status().isForbidden());
    }

    @Test
    void downloadTokensOnlyWorkForDownloads() throws Exception {
        User sup = user("sup", Role.SUPERVISOR, false);
        String t = login(sup);
        Asset a = new Asset();
        a.tag = "SEC-" + sfx;
        a.name = "Asset";
        a = assets.save(a);
        // an uploaded HTML file must download as a file, never render as a page
        MockMultipartFile html = new MockMultipartFile("file", "evil.html", "text/html", "<script>alert(1)</script>".getBytes());
        String doc = mvc.perform(multipart("/api/assets/" + a.id + "/documents").file(html).header("Authorization", "Bearer " + t))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long docId = json.readTree(doc).get("id").asLong();
        String dl = json.readTree(mvc.perform(post("/api/auth/download-token").header("Authorization", "Bearer " + t))
                .andReturn().getResponse().getContentAsString()).get("token").asText();
        String url = "/api/assets/" + a.id + "/documents/" + docId;
        mvc.perform(get(url).param("access_token", dl))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
        // a download token is not a login token, and login tokens don't work in URLs
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + dl)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/work-orders").param("access_token", t)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).param("access_token", t)).andExpect(status().isUnauthorized());
    }
}
