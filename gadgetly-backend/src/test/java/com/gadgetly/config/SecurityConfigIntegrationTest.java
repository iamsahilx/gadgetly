package com.gadgetly.config;

import com.gadgetly.entity.Role;
import com.gadgetly.entity.User;
import com.gadgetly.repository.UserRepository;
import com.gadgetly.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:5173,http://127.0.0.1:5173")
class SecurityConfigIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwt;

    @Test
    void anonymousAdminRequestIsUnauthorized() throws Exception {
        mvc.perform(get("/api/admin/dashboard/stats"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void signedInCustomerCannotAccessAdmin() throws Exception {
        mvc.perform(get("/api/admin/dashboard/stats"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Transactional
    void realCustomerTokenCannotAccessAdmin() throws Exception {
        User customer = new User();
        customer.setFullName("Security test customer");
        customer.setEmail("security-test-" + UUID.randomUUID() + "@example.com");
        customer.setPasswordHash("unused-in-this-test");
        customer.setRole(Role.ROLE_CUSTOMER);
        customer = users.saveAndFlush(customer);
        String token = jwt.generateAccessToken(customer.getEmail(), customer.getRole().name(), customer.getId());

        mvc.perform(get("/api/admin/dashboard/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void bothDocumentedLocalOriginsCanCallApi() throws Exception {
        for (String origin : new String[]{"http://localhost:5173", "http://127.0.0.1:5173"}) {
            mvc.perform(options("/api/products")
                            .header("Origin", origin)
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin));
        }
    }
}
