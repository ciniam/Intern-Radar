package com.ciniam.internradar.controller;

import com.ciniam.internradar.model.AgentRunView;
import com.ciniam.internradar.model.SysUser;
import com.ciniam.internradar.security.AuthGuard;
import com.ciniam.internradar.service.AgentTraceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 智能体执行轨迹查询。默认只看自己的运行记录，导师可以查看他人
 */
@RestController
@RequestMapping("/agent")
public class AgentTraceController {

    private final AgentTraceService agentTraceService;
    private final AuthGuard authGuard;

    public AgentTraceController(AgentTraceService agentTraceService, AuthGuard authGuard) {
        this.agentTraceService = agentTraceService;
        this.authGuard = authGuard;
    }

    @GetMapping("/runs")
    public List<AgentRunView> runs(@RequestParam(required = false) String scene,
                                   @RequestParam(required = false) Integer limit) {
        return agentTraceService.list(authGuard.currentUserId(), scene, limit);
    }

    @GetMapping("/runs/{id}")
    public AgentRunView detail(@PathVariable Long id) {
        AgentRunView run = agentTraceService.detail(id);
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "执行轨迹不存在：" + id);
        }
        SysUser viewer = authGuard.current();
        if (!viewer.userId().equals(run.userId()) && !viewer.canViewTeam()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看他人的执行轨迹");
        }
        return run;
    }
}
