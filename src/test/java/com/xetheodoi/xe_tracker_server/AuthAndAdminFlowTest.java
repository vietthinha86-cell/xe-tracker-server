package com.xetheodoi.xe_tracker_server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthAndAdminFlowTest {

    @Mock
    private AppUserRepository userRepository;

    @Test
    void passwordHashingAndHmacTokenAuthenticationWork() {
        AuthService authService = new AuthService(userRepository, "TestSecretKey_ForUnitTests_32BytesLong!");

        String hash = authService.hashPassword("MatKhau123");
        assertTrue(authService.verifyPassword("MatKhau123", hash));
        assertFalse(authService.verifyPassword("SaiMatKhau", hash));

        AppUser user = new AppUser();
        user.setId(7L);
        user.setUsername("khachhang01");
        user.setRole("USER");
        user.setActive(true);

        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        String token = authService.generateToken(user);
        Optional<AppUser> authed = authService.authenticateRequest("Bearer " + token);
        assertTrue(authed.isPresent());
        assertEquals("khachhang01", authed.get().getUsername());

        // Chỉnh sửa token -> bị từ chối
        assertTrue(authService.authenticateRequest("Bearer " + token + "x").isEmpty());

        // Tài khoản bị khóa -> từ chối dù token đúng chữ ký
        user.setActive(false);
        assertTrue(authService.authenticateRequest("Bearer " + token).isEmpty());
    }

    @Test
    void adminEndpointBlocksStandardUsers() {
        AuthService authService = new AuthService(userRepository, "TestSecretKey_ForUnitTests_32BytesLong!");
        AppUser regularUser = new AppUser();
        regularUser.setId(2L);
        regularUser.setUsername("khachhang");
        regularUser.setRole("USER");
        regularUser.setActive(true);

        when(userRepository.findById(2L)).thenReturn(Optional.of(regularUser));
        String userToken = authService.generateToken(regularUser);

        AdminController adminController = new AdminController(
                userRepository, null, null, null, authService, null
        );

        ResponseEntity<Map<String, Object>> unauth = adminController.getOverview(null);
        assertEquals(HttpStatus.UNAUTHORIZED, unauth.getStatusCode());

        ResponseEntity<Map<String, Object>> forbidden = adminController.getOverview("Bearer " + userToken);
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
    }
}

