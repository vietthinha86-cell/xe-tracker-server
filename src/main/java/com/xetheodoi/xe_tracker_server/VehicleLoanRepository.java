package com.xetheodoi.xe_tracker_server;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VehicleLoanRepository extends JpaRepository<VehicleLoan, Long> {
    List<VehicleLoan> findByVehicleIdOrderByIdDesc(String vehicleId);
    Optional<VehicleLoan> findFirstByVehicleIdAndStatusOrderByIdDesc(String vehicleId, String status);
    List<VehicleLoan> findByStatusOrderByIdDesc(String status);
    List<VehicleLoan> findByVehicleIdInOrderByIdDesc(List<String> vehicleIds);
    void deleteByVehicleId(String vehicleId);
}
