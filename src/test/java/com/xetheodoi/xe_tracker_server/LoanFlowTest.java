package com.xetheodoi.xe_tracker_server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoanFlowTest {

    @Mock
    private VehicleLoanRepository loanRepository;
    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private AppUserRepository userRepository;

    @Test
    void ownerCanStartAndReturnLoanAndPreviousActiveLoanAutoCloses() {
        AuthService authService = new AuthService(userRepository, "TestSecretKey_ForUnitTests_32BytesLong!");
        LoanController controller = new LoanController(loanRepository, vehicleRepository, authService);

        AppUser owner = new AppUser();
        owner.setId(10L);
        owner.setUsername("owner_a");
        owner.setRole("USER");
        owner.setActive(true);
        when(userRepository.findById(10L)).thenReturn(Optional.of(owner));
        String token = "Bearer " + authService.generateToken(owner);

        Vehicle car = new Vehicle();
        car.setVehicleId("60A12345");
        car.setOwnerId(10L);
        car.setMaxSpeedAlertKmh(90);

        VehicleLoan previous = new VehicleLoan();
        previous.setId(1L);
        previous.setVehicleId("60A12345");
        previous.setBorrowerName("Người Mượn Cũ");
        previous.setStatus(VehicleLoan.STATUS_ACTIVE);

        when(vehicleRepository.findByVehicleId("60A12345")).thenReturn(Optional.of(car));
        when(loanRepository.findFirstByVehicleIdAndStatusOrderByIdDesc("60A12345", VehicleLoan.STATUS_ACTIVE))
                .thenReturn(Optional.of(previous));
        when(loanRepository.save(any(VehicleLoan.class))).thenAnswer(inv -> {
            VehicleLoan l = inv.getArgument(0);
            if (l.getId() == null) l.setId(2L);
            return l;
        });

        LoanController.LoanCreateRequest req = new LoanController.LoanCreateRequest();
        req.setVehicleId("60a12345");
        req.setBorrowerName("Nguyễn Văn B");
        req.setBorrowerPhone("0909123456");
        req.setExpectedReturnAt("2026-11-22 18:00");
        req.setMaxSpeedAlertKmh(75);

        ResponseEntity<Map<String, Object>> created = controller.startLoan(req, token);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        assertEquals(VehicleLoan.STATUS_RETURNED, previous.getStatus());
        assertNotNull(previous.getReturnedAt());
        assertEquals(75, car.getMaxSpeedAlertKmh());

        VehicleLoan activeLoan = (VehicleLoan) created.getBody().get("loan");
        assertEquals(2L, activeLoan.getId());
        assertEquals("Nguyễn Văn B", activeLoan.getBorrowerName());
        assertEquals(VehicleLoan.STATUS_ACTIVE, activeLoan.getStatus());

        when(loanRepository.findById(2L)).thenReturn(Optional.of(activeLoan));
        ResponseEntity<Map<String, Object>> returned = controller.returnLoan(2L, Map.of("returnNote", "Xe nguyên vẹn"), token);
        assertEquals(HttpStatus.OK, returned.getStatusCode());
        assertEquals(VehicleLoan.STATUS_RETURNED, activeLoan.getStatus());
        assertTrue(activeLoan.getBorrowerNote().contains("Xe nguyên vẹn"));
    }

    @Test
    void nonOwnerCannotStartOrListLoansForAnotherOwnersCar() {
        AuthService authService = new AuthService(userRepository, "TestSecretKey_ForUnitTests_32BytesLong!");
        LoanController controller = new LoanController(loanRepository, vehicleRepository, authService);

        AppUser stranger = new AppUser();
        stranger.setId(99L);
        stranger.setUsername("stranger");
        stranger.setRole("USER");
        stranger.setActive(true);
        when(userRepository.findById(99L)).thenReturn(Optional.of(stranger));
        String token = "Bearer " + authService.generateToken(stranger);

        Vehicle car = new Vehicle();
        car.setVehicleId("60A12345");
        car.setOwnerId(10L);
        when(vehicleRepository.findByVehicleId("60A12345")).thenReturn(Optional.of(car));

        LoanController.LoanCreateRequest req = new LoanController.LoanCreateRequest();
        req.setVehicleId("60A12345");
        req.setBorrowerName("Hacker");

        ResponseEntity<Map<String, Object>> resp = controller.startLoan(req, token);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());

        ResponseEntity<Map<String, Object>> listResp = controller.listLoans("60A12345", null, token);
        assertEquals(HttpStatus.FORBIDDEN, listResp.getStatusCode());
    }
}
