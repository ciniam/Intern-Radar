package com.ciniam.internradar.controller;

import com.ciniam.internradar.entity.RiskAlertEntity;
import com.ciniam.internradar.model.InternStatus;
import com.ciniam.internradar.model.MentorOverview;
import com.ciniam.internradar.service.MentorViewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 导师视图接口。整个前缀只对导师开放，teamId 仅作预留
 */
@RestController
@RequestMapping("/mentor")
public class MentorController {

    private final MentorViewService mentorViewService;

    public MentorController(MentorViewService mentorViewService) {
        this.mentorViewService = mentorViewService;
    }

    @GetMapping("/overview")
    public MentorOverview overview(@RequestParam(required = false) Long teamId,
                                   @RequestParam(defaultValue = "7") int days) {
        return mentorViewService.overview(days);
    }

    @GetMapping("/interns")
    public List<InternStatus> interns(@RequestParam(defaultValue = "7") int days) {
        return mentorViewService.interns(days);
    }

    @GetMapping("/trend")
    public List<Map<String, Object>> trend(@RequestParam(defaultValue = "14") int days) {
        return mentorViewService.trend(days);
    }

    @GetMapping("/alerts")
    public List<RiskAlertEntity> alerts() {
        return mentorViewService.openAlerts();
    }
}
