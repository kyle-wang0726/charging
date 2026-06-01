package com.charging.backend.controller;

import com.charging.backend.dto.ApiResponse;
import com.charging.backend.model.FaultDispatchStrategy;
import com.charging.backend.model.PileState;
import com.charging.backend.service.StationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final StationService stationService;

    public AdminController(StationService stationService) {
        this.stationService = stationService;
    }

    @GetMapping("/piles")
    public ApiResponse<List<Map<String, Object>>> piles() {
        return ApiResponse.ok(stationService.getPileStatuses());
    }

    @PostMapping("/pile-state")
    public ApiResponse<Void> changeState(@RequestBody Map<String, Object> body) {
        String pileId = str(body.get("pileId"));
        PileState state = PileState.valueOf(str(body.get("state")).toUpperCase());
        stationService.changePileState(pileId, state);
        return ApiResponse.ok("pile state updated", null);
    }

    @PostMapping("/fault-strategy")
    public ApiResponse<Map<String, Object>> setStrategy(@RequestBody Map<String, Object> body) {
        FaultDispatchStrategy strategy = FaultDispatchStrategy.valueOf(str(body.get("strategy")).toUpperCase());
        stationService.setFaultDispatchStrategy(strategy);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("strategy", stationService.getFaultDispatchStrategy());
        return ApiResponse.ok("fault strategy updated", data);
    }

    @GetMapping("/fault-strategy")
    public ApiResponse<Map<String, Object>> getStrategy() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("strategy", stationService.getFaultDispatchStrategy());
        return ApiResponse.ok(data);
    }

    @PostMapping("/config")
    public ApiResponse<Map<String, Object>> config(@RequestBody Map<String, Object> body) {
        Integer waitingAreaSize = body.containsKey("waitingAreaSize")
                ? Integer.parseInt(String.valueOf(body.get("waitingAreaSize"))) : null;
        Integer chargingQueueLen = body.containsKey("chargingQueueLen")
                ? Integer.parseInt(String.valueOf(body.get("chargingQueueLen"))) : null;
        Integer fastChargingPileNum = body.containsKey("fastChargingPileNum")
                ? Integer.parseInt(String.valueOf(body.get("fastChargingPileNum"))) : null;
        Integer slowChargingPileNum = body.containsKey("slowChargingPileNum")
                ? Integer.parseInt(String.valueOf(body.get("slowChargingPileNum"))) : null;
        Double fastPower = body.containsKey("fastPower")
                ? Double.parseDouble(String.valueOf(body.get("fastPower"))) : null;
        Double slowPower = body.containsKey("slowPower")
                ? Double.parseDouble(String.valueOf(body.get("slowPower"))) : null;

        stationService.updateConfig(
                waitingAreaSize,
                chargingQueueLen,
                fastChargingPileNum,
                slowChargingPileNum,
                fastPower,
                slowPower
        );
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("config", stationService.getConfig());
        return ApiResponse.ok("config updated", data);
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> getConfig() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("config", stationService.getConfig());
        return ApiResponse.ok(data);
    }

    @GetMapping("/report")
    public ApiResponse<List<Map<String, Object>>> report(@RequestParam(value = "period", defaultValue = "day") String period) {
        return ApiResponse.ok(stationService.report(period));
    }

    @GetMapping("/time")
    public ApiResponse<Map<String, Object>> getSystemTime() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("systemTime", stationService.getSystemTime());
        return ApiResponse.ok(data);
    }

    @PostMapping("/time/advance")
    public ApiResponse<Map<String, Object>> advanceTime(@RequestBody Map<String, Object> body) {
        long minutes = Long.parseLong(String.valueOf(body.getOrDefault("minutes", 10)));
        LocalDateTime current = stationService.advanceTime(minutes);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("systemTime", current);
        data.put("minutes", minutes);
        return ApiResponse.ok("system time advanced", data);
    }

    private String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
