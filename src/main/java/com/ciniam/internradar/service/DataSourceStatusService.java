package com.ciniam.internradar.service;

import com.ciniam.internradar.entity.CollectLogEntity;
import com.ciniam.internradar.mapper.CollectLogMapper;
import com.ciniam.internradar.model.DataSourceCredential;
import com.ciniam.internradar.model.DataSourceStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源状态。只读展示，让人知道数据从哪来、为什么是空的
 */
@Service
public class DataSourceStatusService {

    private static final String SOURCE_GIT = "git";
    private static final String SOURCE_ZENTAO = "zentao";
    private static final String SOURCE_CHAT = "feishu-chat";

    private final CollectLogMapper collectLogMapper;
    private final PreferenceService preferenceService;
    private final ChatBindingService chatBindingService;

    public DataSourceStatusService(CollectLogMapper collectLogMapper,
                                   PreferenceService preferenceService,
                                   ChatBindingService chatBindingService) {
        this.collectLogMapper = collectLogMapper;
        this.preferenceService = preferenceService;
        this.chatBindingService = chatBindingService;
    }

    public List<DataSourceStatus> list(String userId) {
        Map<String, CollectLogEntity> latest = latestBySource(userId);
        List<DataSourceStatus> statuses = new ArrayList<>();

        List<String> authors = preferenceService.gitAuthors(userId);
        List<String> repositories = preferenceService.gitRepositories(userId);
        boolean gitReady = !authors.isEmpty() && !repositories.isEmpty();
        statuses.add(build(SOURCE_GIT, "代码提交", gitReady,
                gitReady
                        ? "作者标识：" + String.join("、", authors) + "，关联 " + repositories.size() + " 个本地仓库"
                        : !authors.isEmpty() ? "未关联任何本地仓库，读取不到提交" : "未配置提交作者标识，采集不到提交",
                latest));

        DataSourceCredential credential = preferenceService.zentaoCredential(userId);
        statuses.add(build(SOURCE_ZENTAO, "内部任务系统", credential.usable(),
                credential.usable()
                        ? "凭据来源：" + credential.origin() + "，账号：" + credential.account()
                        : "未配置可用账号",
                latest));

        int bindings = chatBindingService.list(userId).size();
        statuses.add(build(SOURCE_CHAT, "会话采集", bindings > 0,
                bindings > 0 ? "已绑定 " + bindings + " 个会话" : "尚未绑定任何会话", latest));

        String openId = preferenceService.openId(userId);
        statuses.add(new DataSourceStatus("push", "消息推送",
                openId != null && !openId.isBlank(),
                openId == null || openId.isBlank() ? "未配置飞书用户标识，无法私发" : "可私发到 " + openId,
                null, null, null, null, null, null));
        return statuses;
    }

    private Map<String, CollectLogEntity> latestBySource(String userId) {
        Map<String, CollectLogEntity> map = new LinkedHashMap<>();
        try {
            for (CollectLogEntity entity : collectLogMapper.selectLatestPerSource(userId)) {
                map.put(entity.getSource(), entity);
            }
        } catch (Exception e) {
            // 状态接口不该因为查不到记录而失败
        }
        return map;
    }

    private DataSourceStatus build(String source, String label, boolean configured,
                                   String detail, Map<String, CollectLogEntity> latest) {
        CollectLogEntity entity = latest.get(source);
        if (entity == null) {
            return new DataSourceStatus(source, label, configured, detail,
                    null, null, null, null, null, null);
        }
        return new DataSourceStatus(source, label, configured, detail,
                entity.getStatDate(), entity.getCreatedAt(), entity.getFetched(), entity.getInserted(),
                entity.getSuccess(), entity.getErrorMsg());
    }
}
