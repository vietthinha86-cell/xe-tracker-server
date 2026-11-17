package com.xetheodoi.xe_tracker_server;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsernameIgnoreCase(String username);
    boolean existsByRole(String role);
    List<AppUser> findAllByOrderByIdAsc();
}

