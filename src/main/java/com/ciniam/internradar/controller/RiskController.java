package com.ciniam.internradar.controller;

import com.ciniam.internradar.entity.RiskAlertEntity;
import com.ciniam.internradar.model.RiskAlert;
import com.ciniam.internradar.security.AuthGuard;
import com.ciniam.internradar.service.RiskService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

/**
 * 风险相关接口。userId 缺省时按登录人处理，确认告警前先核对归属
 */
@RestController
@RequestMapping("/risk")
public class RiskController {

    private final RiskService riskService;
    private final AuthGuard authGuard;

    public RiskController(RiskService riskService, AuthGuard authGuard) {
        this.riskService = riskService;
        this.authGuard = authGuard;
    }

    /**
     * 手动跑一次风险检查，返回本次新增的告警
     */
    @PostMapping("/check")
    public List<RiskAlert> check(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return riskService.evaluate(authGuard.resolveUserId(userId), date == null ? LocalDate.now() : date);
    }

    @PostMapping("/notify")
    public int notify(@RequestParam(required = false) String userId) {
        return riskService.notifyPending(authGuard.resolveUserId(userId));
    }

    @GetMapping("/alerts")
    public List<RiskAlertEntity> alerts(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false, defaultValue = "OPEN") String status) {
        return riskService.listAlerts(authGuard.resolveUserId(userId), status);
    }

    @PostMapping("/{id}/ack")
    public boolean acknowledge(@PathVariable Long id) {
        RiskAlertEntity alert = riskService.findById(id);
        if (alert == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "告警不存在：" + id);
        }
        authGuard.requireSelfOrMentor(alert.getUserId(), "告警");
        return riskService.acknowledge(id);
    }
}
