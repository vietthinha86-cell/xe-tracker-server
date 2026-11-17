package com.xetheodoi.xe_tracker_server;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface LocationHistoryRepository extends JpaRepository<LocationHistory, Long> {
    List<LocationHistory> findByVehicleIdOrderByIdAsc(String vehicleId);
    List<LocationHistory> findByVehicleIdAndRecordedAtStartingWithOrderByIdAsc(String vehicleId, String datePrefix);
    Optional<LocationHistory> findTopByVehicleIdOrderByIdDesc(String vehicleId);
    void deleteByVehicleId(String vehicleId);
}
