package com.xetheodoi.xe_tracker_server;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaptureCommandRepository extends JpaRepository<CaptureCommand, Long> {
    List<CaptureCommand> findByVehicleIdAndStatusOrderByIdAsc(String vehicleId, String status);
    List<CaptureCommand> findTop15ByVehicleIdOrderByIdDesc(String vehicleId);
    void deleteByVehicleId(String vehicleId);
}
