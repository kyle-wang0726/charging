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

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
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
        String vehicleNumber = str(body.get("vehicleNumber"));
        ChargingRequest req = stationService.submitRequest(userId, mode, requestKwh, batteryCapacityKwh, vehicleNumber);
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

    @GetMapping("/bills/export")
    public void exportBills(@RequestParam("userId") Long userId, HttpServletResponse response) throws IOException {
        List<ChargeBill> bills = stationService.getUserBills(userId);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"charging-bills.csv\"");
        PrintWriter pw = response.getWriter();
        pw.write('﻿');
        pw.println("详单号,订单号,车辆编号,详单状态,生成时间,桩编号,电量(kWh),时长(h),开始时间,停止时间,充电费(元),服务费(元),总费用(元)");
        for (ChargeBill b : bills) {
            pw.println(join(
                csvCell(b.getBillNo()),
                csvCell(b.getRequestId()),
                csvCell(b.getVehicleNumber()),
                csvCell(b.getBillStatus()),
                csvCell(formatTime(b.getGeneratedAt())),
                csvCell(b.getPileId()),
                b.getChargedKwh(),
                b.getChargedHours(),
                csvCell(formatTime(b.getStartTime())),
                csvCell(formatTime(b.getStopTime())),
                b.getChargeFee(),
                b.getServiceFee(),
                b.getTotalFee()
            ));
        }
        pw.flush();
    }

    private String formatTime(Object time) {
        return time == null ? "" : String.valueOf(time).replace("T", " ");
    }

    private String csvCell(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private String join(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private Map<String, Object> requestData(ChargingRequest req) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", req.getId());
        data.put("queueNumber", req.getQueueNumber());
        data.put("mode", req.getMode());
        data.put("vehicleNumber", req.getVehicleNumber());
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
