package com.xetheodoi.xe_tracker_server;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {
    Optional<Vehicle> findByVehicleId(String vehicleId);
    List<Vehicle> findByOwnerIdOrderByIdAsc(Long ownerId);
    List<Vehicle> findAllByOrderByIdAsc();
    Optional<Vehicle> findByActivationCodeIgnoreCase(String activationCode);
    Optional<Vehicle> findByImeiIgnoreCase(String imei);
}