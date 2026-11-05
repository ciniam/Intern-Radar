package com.ciniam.internradar.controller;

import com.ciniam.internradar.service.ObserveService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 可观测性看板数据
 */
@RestController
@RequestMapping("/admin")
public class ObserveController {

    private final ObserveService observeService;

    public ObserveController(ObserveService observeService) {
        this.observeService = observeService;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics(@RequestParam(defaultValue = "7") int days) {
        return observeService.summary(days);
    }
}
