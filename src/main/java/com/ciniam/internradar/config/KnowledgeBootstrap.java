package com.ciniam.internradar.config;

import com.ciniam.internradar.entity.TeamEntity;
import com.ciniam.internradar.mapper.TeamMapper;
import com.ciniam.internradar.service.KnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时把各团队的知识目录同步进库。同步失败只告警，不影响服务启动
 */
@Component
@Order(20)
public class KnowledgeBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBootstrap.class);

    private final KnowledgeService knowledgeService;
    private final TeamMapper teamMapper;
    private final InternRadarProperties properties;

    public KnowledgeBootstrap(KnowledgeService knowledgeService, TeamMapper teamMapper,
                              InternRadarProperties properties) {
        this.knowledgeService = knowledgeService;
        this.teamMapper = teamMapper;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getKnowledge().isSyncOnStartup()) {
            log.info("知识库启动同步已关闭，跳过");
            return;
        }
        for (TeamEntity team : teamMapper.selectAll()) {
            try {
                knowledgeService.syncFromFiles(team.getId());
            } catch (Exception e) {
                log.warn("知识库启动同步失败，团队 {}，可稍后调用 /learn/docs/refresh 重试：{}",
                        team.getId(), e.getMessage());
            }
        }
    }
}
