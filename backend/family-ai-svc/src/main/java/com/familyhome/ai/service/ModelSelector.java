package com.familyhome.ai.service;

import com.familyhome.ai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 模型选择：首选失败后按候选顺序换下一个。
 *
 * <p>这是「auto」的本地实现。DashScope 的 OpenAI 兼容端点不接受 {@code model=auto}
 * （返回 403 access_denied，与「模型不存在」的 404 是两种错误），所以自动选模型只能
 * 在客户端侧做：依次尝试，谁先成功就用谁。
 *
 * <p>候选顺序 = 上次成功的模型 → 配置的首选 → 降级列表。把上次成功的挪到最前，
 * 是为了避免每轮对话都先撞一次 403 再降级——那会白白多一次往返和一条告警日志。
 */
@Component
public class ModelSelector {

    private static final Logger log = LoggerFactory.getLogger(ModelSelector.class);

    /** 没配 {@code family.ai.models} 时用这套顺序：质量相近、可用性互不重叠。 */
    private static final String DEFAULT_FALLBACKS = "qwen-flash,qwen-max,qwen-long";

    private final AiProperties properties;

    /** 上次成功的模型；null 表示还没成功过。 */
    private volatile String preferred;

    @Autowired
    public ModelSelector(AiProperties properties) {
        this.properties = properties;
    }

    /** 本轮要依次尝试的模型，首项优先。 */
    public List<String> candidates() {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        String lastGood = preferred;
        if (lastGood != null && !lastGood.isBlank()) ordered.add(lastGood);
        ordered.add(properties.model());
        String extra = properties.models() == null || properties.models().isBlank()
                ? DEFAULT_FALLBACKS
                : properties.models();
        for (String item : extra.split(",")) {
            if (!item.isBlank()) ordered.add(item.trim());
        }
        return List.copyOf(ordered);
    }

    /** 记录一次成功：后续优先用它，省掉前面那些必然失败的尝试。 */
    public void markSuccess(String model) {
        if (model == null || model.isBlank() || model.equals(preferred)) return;
        if (preferred == null) {
            log.info("AI 模型：{}", model);
        } else {
            log.info("AI 模型切换到 {}（上一个 {} 不可用或被限流）", model, preferred);
        }
        preferred = model;
    }

    /** 当前优先使用的模型，供管理端回显。 */
    public String current() {
        String lastGood = preferred;
        return lastGood == null || lastGood.isBlank() ? properties.model() : lastGood;
    }

    /** 展示用的候选清单（不含缓存影响，按配置顺序）。 */
    public List<String> configured() {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        ordered.add(properties.model());
        String extra = properties.models() == null || properties.models().isBlank()
                ? DEFAULT_FALLBACKS
                : properties.models();
        for (String item : extra.split(",")) {
            if (!item.isBlank()) ordered.add(item.trim());
        }
        return new ArrayList<>(ordered);
    }
}
