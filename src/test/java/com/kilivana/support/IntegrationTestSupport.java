package com.kilivana.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kilivana.auth.api.AuthResponse;
import com.kilivana.auth.api.LoginRequest;
import com.kilivana.auth.api.LogoutRequest;
import com.kilivana.auth.api.RefreshTokenRequest;
import com.kilivana.auth.api.SignupRequest;
import com.kilivana.users.domain.DriverProfile;
import com.kilivana.users.domain.InspectorProfile;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import com.kilivana.users.repository.DriverProfileRepository;
import com.kilivana.users.repository.InspectorProfileRepository;
import com.kilivana.users.repository.UserRepository;
import com.kilivana.products.api.CreateProductRequest;
import com.kilivana.products.api.ProductResponse;
import com.kilivana.orders.api.CreateOrderRequest;
import com.kilivana.orders.api.OrderResponse;
import com.kilivana.orders.api.UpdateOrderStatusRequest;
import com.kilivana.orders.domain.OrderStatus;
import com.kilivana.payments.api.CreatePaymentRequest;
import com.kilivana.payments.api.PaymentResponse;
import com.kilivana.payments.api.VerifyPaymentRequest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestSupport {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRESQL_CONTAINER;

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS_CONTAINER;

    static {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine");
        container.start();
        POSTGRESQL_CONTAINER = container;
        GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine");
        redis.withExposedPorts(6379);
        redis.start();
        REDIS_CONTAINER = redis;
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InspectorProfileRepository inspectorProfileRepository;

    @Autowired
    private DriverProfileRepository driverProfileRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL_CONTAINER::getPassword);
        registry.add("kilivana.security.jwt.secret", () -> "test-secret-key-that-is-at-least-thirty-two-bytes-long");
        registry.add("kilivana.security.admin.bootstrap-key", () -> "test-admin-bootstrap-key");
        registry.add("kilivana.media.root-dir",
                () -> Path.of(System.getProperty("java.io.tmpdir"), "kilivana-uploads").toString());
        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(REDIS_CONTAINER.getMappedPort(6379)));
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
                truncate table
                    tracking_events,
                    logistics_jobs,
                    visit_photos,
                    audit_reports,
                    site_visit_requests,
                    contact_messages,
                    inspection_checklist_questions,
                    inspection_checklists,
                    inspections,
                    user_badges,
                    product_images,
                    products,
                    cart_items,
                    customer_orders,
                    order_items,
                    orders,
                    payments,
                    password_reset_tokens,
                    addresses,
                    driver_profiles,
                    refresh_tokens,
                    buyer_profiles,
                    supplier_profiles,
                    farmer_profiles,
                    inspector_profiles,
                    users
                restart identity cascade
                """);
        redisConnectionFactory.getConnection().serverCommands().flushDb();
    }

    protected AuthResponse signup(SignupRequest request) throws Exception {
        return readBody(mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andReturn(), AuthResponse.class);
    }

    protected AuthResponse privilegedSignup(SignupRequest request) throws Exception {
        if (request.role() != UserRole.INSPECTOR && request.role() != UserRole.DRIVER) {
            throw new IllegalArgumentException("Only staff roles can use privilegedSignup");
        }
        User user = userRepository.save(new User(
                request.email().trim().toLowerCase(),
                passwordEncoder.encode(request.password()),
                request.fullName(),
                request.phoneNumber(),
                request.role(),
                UserStatus.ACTIVE));
        if (request.role() == UserRole.INSPECTOR) {
            inspectorProfileRepository.save(new InspectorProfile(user, request.employeeCode().trim()));
        } else {
            driverProfileRepository.save(new DriverProfile(
                    user,
                    request.licenseNumber().trim(),
                    request.vehicleType().trim(),
                    request.vehiclePlate().trim(),
                    request.serviceArea() == null ? null : request.serviceArea().trim()));
        }
        return readBody(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new LoginRequest(
                                request.email(),
                                request.password()))))
                .andExpect(status().isOk())
                .andReturn(), AuthResponse.class);
    }

    protected AuthResponse refresh(String refreshToken) throws Exception {
        return readBody(mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new RefreshTokenRequest(refreshToken))))
                .andExpect(status().isOk())
                .andReturn(), AuthResponse.class);
    }

    protected void logout(String refreshToken) throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new LogoutRequest(refreshToken))))
                .andExpect(status().isNoContent());
    }

    protected ProductResponse createProduct(String accessToken, CreateProductRequest request) throws Exception {
        return readBody(mockMvc.perform(authorized(post("/api/v1/products"), accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andReturn(), ProductResponse.class);
    }

    protected List<ProductResponse> browseProducts() throws Exception {
        return readBody(mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andReturn(), new TypeReference<>() {
        });
    }

    protected OrderResponse createOrder(String accessToken, CreateOrderRequest request) throws Exception {
        return readBody(mockMvc.perform(authorized(post("/api/v1/orders"), accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andReturn(), OrderResponse.class);
    }

    protected OrderResponse updateOrderStatus(String accessToken, Object orderId, OrderStatus statusValue) throws Exception {
        return readBody(mockMvc.perform(authorized(patch("/api/v1/orders/{orderId}/status", orderId), accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new UpdateOrderStatusRequest(statusValue))))
                .andExpect(status().isOk())
                .andReturn(), OrderResponse.class);
    }

    protected OrderResponse cancelOrder(String accessToken, Object orderId) throws Exception {
        return readBody(mockMvc.perform(authorized(post("/api/v1/orders/{orderId}/cancel", orderId), accessToken))
                .andExpect(status().isOk())
                .andReturn(), OrderResponse.class);
    }

    protected PaymentResponse createPayment(String accessToken, CreatePaymentRequest request) throws Exception {
        return readBody(mockMvc.perform(authorized(post("/api/v1/payments"), accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andReturn(), PaymentResponse.class);
    }

    protected PaymentResponse verifyPayment(String accessToken, String idempotencyKey) throws Exception {
        return readBody(mockMvc.perform(authorized(post("/api/v1/payments/verify"), accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new VerifyPaymentRequest(idempotencyKey))))
                .andExpect(status().isOk())
                .andReturn(), PaymentResponse.class);
    }

    protected MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder, String accessToken) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
    }

    protected <T> T readBody(MvcResult result, Class<T> type) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), type);
    }

    protected <T> T readBody(MvcResult result, TypeReference<T> typeReference) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), typeReference);
    }
}
