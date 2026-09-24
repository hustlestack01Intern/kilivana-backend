package com.kilivana.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.api.PublicSellerProfileResponse;
import com.kilivana.users.api.UserProfileResponse;
import com.kilivana.users.domain.DriverProfile;
import com.kilivana.users.domain.SupplierProfile;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import com.kilivana.users.repository.DriverProfileRepository;
import com.kilivana.users.repository.FarmerProfileRepository;
import com.kilivana.users.repository.InspectorProfileRepository;
import com.kilivana.users.repository.SupplierProfileRepository;
import com.kilivana.users.repository.UserRepository;
import com.kilivana.users.service.UserService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserProfileSecurityTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private SupplierProfileRepository supplierProfileRepository;

    @Mock
    private FarmerProfileRepository farmerProfileRepository;

    @Mock
    private InspectorProfileRepository inspectorProfileRepository;

    @Mock
    private DriverProfileRepository driverProfileRepository;

    @Mock
    private CurrentUser currentUser;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository,
                supplierProfileRepository,
                farmerProfileRepository,
                inspectorProfileRepository,
                driverProfileRepository,
                currentUser);
    }

    @Test
    void privateProfileCanOnlyBeReadByItsOwnerOrAnAdmin() {
        UUID ownerId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        User owner = supplier(ownerId);
        when(currentUser.required()).thenReturn(new AuthenticatedUser(
                otherId,
                "other@example.com",
                "hash",
                UserRole.BUYER));

        assertThatThrownBy(() -> userService.profileById(ownerId))
                .isInstanceOf(UnauthorizedOperationException.class);
        verify(userRepository, never()).findById(ownerId);
    }

    @Test
    void publicSellerProfileDoesNotExposePrivateContactData() {
        UUID sellerId = UUID.randomUUID();
        User seller = supplier(sellerId);
        when(userRepository.findById(sellerId)).thenReturn(Optional.of(seller));
        when(supplierProfileRepository.findByUserId(sellerId))
                .thenReturn(Optional.of(new SupplierProfile(seller, "Green Supply")));

        PublicSellerProfileResponse response = userService.publicSellerProfile(sellerId);

        assertThat(response.businessName()).isEqualTo("Green Supply");
        assertThat(response.displayName()).isEqualTo("Seller");
        assertThat(response.toString()).doesNotContain("seller@example.com", "+254700000000");
    }

    @Test
    void publicSellerProfileRejectsDriverAndInspectorData() {
        UUID userId = UUID.randomUUID();
        User driver = new User(
                "driver@example.com",
                "hash",
                "Driver",
                "+254700000001",
                UserRole.DRIVER,
                UserStatus.ACTIVE);
        ReflectionTestUtils.setField(driver, "id", userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(driver));

        assertThatThrownBy(() -> userService.publicSellerProfile(userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void ownerCanReadTheirOwnFullPrivateProfile() {
        UUID ownerId = UUID.randomUUID();
        User owner = supplier(ownerId);
        when(currentUser.required()).thenReturn(new AuthenticatedUser(
                ownerId,
                owner.getEmail(),
                "hash",
                UserRole.SUPPLIER));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(supplierProfileRepository.findByUserId(ownerId))
                .thenReturn(Optional.of(new SupplierProfile(owner, "Green Supply")));

        UserProfileResponse response = userService.profileById(ownerId);

        assertThat(response.email()).isEqualTo(owner.getEmail());
        assertThat(response.businessName()).isEqualTo("Green Supply");
    }

    private User supplier(UUID id) {
        User user = new User(
                "seller@example.com",
                "hash",
                "Seller",
                "+254700000000",
                UserRole.SUPPLIER,
                UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
