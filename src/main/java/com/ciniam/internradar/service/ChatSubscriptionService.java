package com.ciniam.internradar.service;

import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.entity.ChatBindingEntity;
import com.ciniam.internradar.mapper.ChatBindingMapper;
import com.ciniam.internradar.model.ChatSubscription;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 待采集会话的统一来源，合并配置文件与数据库白名单
 * 同一个 chat_id 以数据库为准，便于线上随时增删而不用改配置重启
 */
@Service
public class ChatSubscriptionService {

    private final InternRadarProperties properties;
    private final ChatBindingMapper chatBindingMapper;

    public ChatSubscriptionService(InternRadarProperties properties, ChatBindingMapper chatBindingMapper) {
        this.properties = properties;
        this.chatBindingMapper = chatBindingMapper;
    }

    public List<ChatSubscription> forUser(String userId) {
        Map<String, ChatSubscription> merged = new LinkedHashMap<>();
        for (InternRadarProperties.ChatTarget target : properties.getFeishu().getChats()) {
            if (!target.isEnabled() || !userId.equals(target.getUserId())) {
                continue;
            }
            merged.put(target.getChatId(), new ChatSubscription(userId, target.getChatId(),
                    target.getName(), target.getType(), target.getIdentity()));
        }
        for (ChatBindingEntity binding : chatBindingMapper.selectByUser(userId)) {
            if (!Boolean.TRUE.equals(binding.getEnabled())) {
                continue;
            }
            merged.put(binding.getChatId(), new ChatSubscription(userId, binding.getChatId(),
                    binding.getName(), binding.getChatType(), binding.getIdentity()));
        }
        return new ArrayList<>(merged.values());
    }

    public boolean anyConfigured() {
        if (!properties.getFeishu().getChats().isEmpty()) {
            return true;
        }
        return !chatBindingMapper.selectEnabled().isEmpty();
    }
}
