package com.familyhome.media.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 通话录音转写用的线程池。
 *
 * <p>刻意开得很小：转写是长耗时 IO 任务，一次家庭通话就占一个线程；
 * 排队上限兜住突发提交，避免把容器线程和第三方额度一次打满。
 * 队列满时用 CallerRuns 回压——提交录音的请求慢一点，好过任务被静默丢弃。
 */
@Configuration
@EnableAsync
public class RecordingAsyncConfig {

    @Bean("recordingExecutor")
    public Executor recordingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(40);
        executor.setKeepAliveSeconds(120);
        executor.setThreadNamePrefix("call-recording-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
