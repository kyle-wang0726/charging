package com.charging.backend.controller;

import com.charging.backend.dto.ApiResponse;
import com.charging.backend.model.UserAccount;
import com.charging.backend.service.StationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final StationService stationService;

    public AuthController(StationService stationService) {
        this.stationService = stationService;
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        String username = str(body.get("username"));
        String password = str(body.get("password"));
        double batteryCapacityKwh = num(body.get("batteryCapacityKwh"), 60.0);
        UserAccount user = stationService.register(username, password, batteryCapacityKwh);
        return ApiResponse.ok("register success", userData(user));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        String username = str(body.get("username"));
        String password = str(body.get("password"));
        UserAccount user = stationService.login(username, password);
        return ApiResponse.ok("login success", userData(user));
    }

    private Map<String, Object> userData(UserAccount user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("batteryCapacityKwh", user.getBatteryCapacityKwh());
        return data;
    }

    private String str(Object v) {
        if (v == null) {
            return "";
        }
        return String.valueOf(v).trim();
    }

    private double num(Object v, double dft) {
        if (v == null) {
            return dft;
        }
        return Double.parseDouble(String.valueOf(v));
    }
}
