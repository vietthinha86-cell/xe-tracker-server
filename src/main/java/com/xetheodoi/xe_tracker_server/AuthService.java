package com.xetheodoi.xe_tracker_server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Xử lý băm mật khẩu PBKDF2WithHmacSHA256 và ký/xác thực token HMAC-SHA256 không trạng thái.
 */
@Service
public class AuthService {

    private static final int PBKDF2_ITERATIONS = 65536;
    private static final int KEY_LENGTH_BITS = 256;
    private static final long TOKEN_TTL_MS = 30L * 24 * 60 * 60 * 1000; // 30 ngày cho PWA iPhone

    private final AppUserRepository userRepository;
    private final String authSecret;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(
            AppUserRepository userRepository,
            @Value("${app.auth.secret:XeTracker_LocalDev_ChangeMe_KeepMin32Bytes_2026!}") String authSecret
    ) {
        this.userRepository = userRepository;
        this.authSecret = authSecret;
    }

    public String hashPassword(String rawPassword) {
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);
        byte[] derived = deriveKey(rawPassword, salt);
        Base64.Encoder enc = Base64.getEncoder();
        return "pbkdf2$" + enc.encodeToString(salt) + "$" + enc.encodeToString(derived);
    }

    public boolean verifyPassword(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null) return false;
        String[] parts = storedHash.split("\\$");
        if (parts.length != 3 || !"pbkdf2".equals(parts[0])) return false;
        try {
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            byte[] actual = deriveKey(rawPassword, salt);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    public String generateToken(AppUser user) {
        long expMs = System.currentTimeMillis() + TOKEN_TTL_MS;
        String safeUsername = user.getUsername().replace("|", "");
        String safeRole = (user.getRole() != null ? user.getRole() : "USER").replace("|", "");
        String payload = user.getId() + "|" + safeUsername + "|" + safeRole + "|" + expMs;
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String sigB64 = hmacSign(payloadB64);
        return payloadB64 + "." + sigB64;
    }

    public Optional<AppUser> authenticateRequest(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = authHeader.substring(7).trim();
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            return Optional.empty();
        }
        String expectedSig = hmacSign(parts[0]);
        if (!MessageDigest.isEqual(
                expectedSig.getBytes(StandardCharsets.UTF_8),
                parts[1].getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String[] fields = decoded.split("\\|");
            if (fields.length != 4) return Optional.empty();
            long userId = Long.parseLong(fields[0]);
            long expMs = Long.parseLong(fields[3]);
            if (System.currentTimeMillis() > expMs) {
                return Optional.empty();
            }
            Optional<AppUser> userOpt = userRepository.findById(userId);
            if (userOpt.isPresent() && userOpt.get().isAccountActive()) {
                return userOpt;
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] deriveKey(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return skf.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Password hashing failed", e);
        }
    }

    private String hmacSign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(authSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }
}

