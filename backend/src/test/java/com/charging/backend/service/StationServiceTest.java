package com.charging.backend.service;

import com.charging.backend.model.ChargeMode;
import com.charging.backend.model.ChargingRequest;
import com.charging.backend.model.PileState;
import com.charging.backend.model.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class StationServiceTest {

    @Test
    void faultPriorityQueueDoesNotOccupyWaitingAreaCapacity() {
        StationService service = new StationService(new BillingService());
        ReflectionTestUtils.setField(service, "logFilePath", "target/test-logs/station.log");
        service.initLogFile();
        service.updateConfig(1, 1, 1, 1, 30.0, 10.0);

        UserAccount first = service.register("first", "password");
        UserAccount waiting = service.register("waiting", "password");
        UserAccount newcomer = service.register("newcomer", "password");

        service.submitRequest(first.getId(), ChargeMode.FAST, 30, 30, "FIRST");
        ChargingRequest waitingRequest =
                service.submitRequest(waiting.getId(), ChargeMode.FAST, 30, 30, "WAITING");

        service.changePileState("F1", PileState.FAULT);
        service.cancelRequest(waiting.getId(), waitingRequest.getId());

        assertDoesNotThrow(() ->
                service.submitRequest(newcomer.getId(), ChargeMode.FAST, 30, 30, "NEWCOMER"));
    }
}
