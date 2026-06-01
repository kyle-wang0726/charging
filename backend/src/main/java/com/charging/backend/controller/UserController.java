package com.charging.backend.controller;

import com.charging.backend.dto.ApiResponse;
import com.charging.backend.model.ChargeBill;
import com.charging.backend.model.ChargeMode;
import com.charging.backend.model.ChargingRequest;
import com.charging.backend.service.StationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {
    private final StationService stationService;

    public UserController(StationService stationService) {
        this.stationService = stationService;
    }

    @PostMapping("/request")
    public ApiResponse<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        Long userId = longNum(body.get("userId"));
        ChargeMode mode = ChargeMode.valueOf(str(body.get("mode")).toUpperCase());
        double requestKwh = doubleNum(body.get("requestKwh"));
        double batteryCapacityKwh = doubleNum(body.get("batteryCapacityKwh"));
        ChargingRequest req = stationService.submitRequest(userId, mode, requestKwh, batteryCapacityKwh);
        return ApiResponse.ok("request submitted", requestData(req));
    }

    @PutMapping("/request")
    public ApiResponse<Map<String, Object>> modify(@RequestBody Map<String, Object> body) {
        Long userId = longNum(body.get("userId"));
        Long requestId = null;
        if (body.containsKey("requestId") && body.get("requestId") != null && !str(body.get("requestId")).isEmpty()) {
            requestId = longNum(body.get("requestId"));
        }
        ChargeMode mode = null;
        if (body.containsKey("mode") && body.get("mode") != null && !str(body.get("mode")).isEmpty()) {
            mode = ChargeMode.valueOf(str(body.get("mode")).toUpperCase());
        }
        Double requestKwh = null;
        if (body.containsKey("requestKwh") && body.get("requestKwh") != null && !str(body.get("requestKwh")).isEmpty()) {
            requestKwh = doubleNum(body.get("requestKwh"));
        }
        Double batteryCapacityKwh = null;
        if (body.containsKey("batteryCapacityKwh") && body.get("batteryCapacityKwh") != null && !str(body.get("batteryCapacityKwh")).isEmpty()) {
            batteryCapacityKwh = doubleNum(body.get("batteryCapacityKwh"));
        }
        ChargingRequest req = stationService.modifyRequest(userId, requestId, mode, requestKwh, batteryCapacityKwh);
        return ApiResponse.ok("request updated", requestData(req));
    }

    @DeleteMapping("/request")
    public ApiResponse<Void> cancel(@RequestParam("userId") Long userId,
                                    @RequestParam(value = "requestId", required = false) Long requestId) {
        stationService.cancelRequest(userId, requestId);
        return ApiResponse.ok("request canceled", null);
    }

    @PostMapping("/end")
    public ApiResponse<ChargeBill> end(@RequestBody Map<String, Object> body) {
        Long userId = longNum(body.get("userId"));
        Long requestId = null;
        if (body.containsKey("requestId") && body.get("requestId") != null && !str(body.get("requestId")).isEmpty()) {
            requestId = longNum(body.get("requestId"));
        }
        ChargeBill bill = stationService.endCharging(userId, requestId);
        return ApiResponse.ok("charging ended", bill);
    }

    @GetMapping("/queue-info")
    public ApiResponse<Map<String, Object>> queueInfo(@RequestParam("userId") Long userId,
                                                      @RequestParam(value = "requestId", required = false) Long requestId) {
        return ApiResponse.ok(stationService.getQueueInfo(userId, requestId));
    }

    @GetMapping("/requests")
    public ApiResponse<List<Map<String, Object>>> requests(@RequestParam("userId") Long userId,
                                                           @RequestParam(value = "includeFinished", defaultValue = "false") boolean includeFinished) {
        return ApiResponse.ok(stationService.getUserRequests(userId, includeFinished));
    }

    @GetMapping("/bills")
    public ApiResponse<List<ChargeBill>> bills(@RequestParam("userId") Long userId) {
        return ApiResponse.ok(stationService.getUserBills(userId));
    }

    private Map<String, Object> requestData(ChargingRequest req) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", req.getId());
        data.put("queueNumber", req.getQueueNumber());
        data.put("mode", req.getMode());
        data.put("batteryCapacityKwh", req.getBatteryCapacityKwh());
        data.put("requestKwh", req.getRequestedKwh());
        data.put("status", req.getStatus());
        data.put("pileId", req.getPileId());
        return data;
    }

    private String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private Long longNum(Object v) {
        return Long.parseLong(str(v));
    }

    private Double doubleNum(Object v) {
        return Double.parseDouble(str(v));
    }
}
