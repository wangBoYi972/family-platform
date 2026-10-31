package com.familyhome.content.config;

import com.familyhome.content.service.ContentFileMappingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时把历史 {@code file_ids} 回填进 {@code content_file_mapping}。
 *
 * <p>幂等：只处理「旧列有值、映射表为空」的行，没有待办时是四次空查询。
 * 回填失败不影响服务启动——读路径本身对旧列有回退，功能不会中断。
 */
@Component
public class ContentFileBackfillRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ContentFileBackfillRunner.class);

    @Autowired private ContentFileMappingService links;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int rows = links.backfillLegacy();
            if (rows > 0) log.info("content_file_mapping 启动回填完成 rows={}", rows);
        } catch (RuntimeException ex) {
            log.warn("content_file_mapping 启动回填失败，下次启动会重试（读路径仍可回退旧列）", ex);
        }
    }
}
