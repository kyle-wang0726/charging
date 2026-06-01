package com.charging.backend.service;

import com.charging.backend.model.ChargeBill;
import com.charging.backend.model.SystemConfig;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Service
public class BillingService {

    public ChargeBill buildBill(String billNo,
                                Long userId,
                                String pileId,
                                LocalDateTime start,
                                LocalDateTime stop,
                                double power,
                                double maxKwh,
                                SystemConfig config) {
        ChargeBill bill = new ChargeBill();
        bill.setBillNo(billNo);
        bill.setGeneratedAt(LocalDateTime.now());
        bill.setUserId(userId);
        bill.setPileId(pileId);
        bill.setStartTime(start);
        bill.setStopTime(stop);

        if (start == null || stop == null || !stop.isAfter(start)) {
            bill.setChargedHours(0);
            bill.setChargedKwh(0);
            bill.setChargeFee(0);
            bill.setServiceFee(0);
            bill.setTotalFee(0);
            return bill;
        }

        double chargedHours = Duration.between(start, stop).toMinutes() / 60.0;
        double chargedKwh = Math.min(maxKwh, chargedHours * power);
        chargedHours = chargedKwh / power;

        double chargeFee = calculateToUChargeFee(start, chargedHours, power);
        double serviceFee = chargedKwh * config.getServiceFeePerKwh();
        double total = chargeFee + serviceFee;

        bill.setChargedHours(round(chargedHours));
        bill.setChargedKwh(round(chargedKwh));
        bill.setChargeFee(round(chargeFee));
        bill.setServiceFee(round(serviceFee));
        bill.setTotalFee(round(total));
        return bill;
    }

    private double calculateToUChargeFee(LocalDateTime start, double chargedHours, double power) {
        double totalFee = 0.0;
        LocalDateTime cursor = start;
        long totalSeconds = Math.round(chargedHours * 3600);
        long consumed = 0L;
        while (consumed < totalSeconds) {
            LocalDateTime boundary = nextBoundary(cursor);
            long segmentSeconds = Math.min(Duration.between(cursor, boundary).getSeconds(), totalSeconds - consumed);
            double rate = energyRate(cursor.toLocalTime());
            double segmentKwh = segmentSeconds / 3600.0 * power;
            totalFee += segmentKwh * rate;
            cursor = cursor.plusSeconds(segmentSeconds);
            consumed += segmentSeconds;
        }
        return totalFee;
    }

    private LocalDateTime nextBoundary(LocalDateTime time) {
        LocalDateTime date = time.withSecond(0).withNano(0);
        LocalDateTime[] boundaries = new LocalDateTime[]{
                date.withHour(7).withMinute(0),
                date.withHour(10).withMinute(0),
                date.withHour(15).withMinute(0),
                date.withHour(18).withMinute(0),
                date.withHour(21).withMinute(0),
                date.withHour(23).withMinute(0),
                date.plusDays(1).withHour(7).withMinute(0)
        };
        for (LocalDateTime boundary : boundaries) {
            if (boundary.isAfter(time)) {
                return boundary;
            }
        }
        return time.plusHours(1);
    }

    private double energyRate(LocalTime t) {
        if (between(t, 10, 0, 15, 0) || between(t, 18, 0, 21, 0)) {
            return 1.0;
        }
        if (between(t, 7, 0, 10, 0) || between(t, 15, 0, 18, 0) || between(t, 21, 0, 23, 0)) {
            return 0.7;
        }
        return 0.4;
    }

    private boolean between(LocalTime t, int sh, int sm, int eh, int em) {
        LocalTime start = LocalTime.of(sh, sm);
        LocalTime end = LocalTime.of(eh, em);
        return !t.isBefore(start) && t.isBefore(end);
    }

    private double round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
