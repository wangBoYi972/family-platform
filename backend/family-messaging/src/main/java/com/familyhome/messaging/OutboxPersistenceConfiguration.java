package com.familyhome.messaging;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 复用业务会话工厂，不额外创建数据源或事务管理器。 */
@Configuration
public class OutboxPersistenceConfiguration {
    @Bean
    public OutboxMapper outboxMapper(SqlSessionFactory factory) {
        return new SqlSessionTemplate(factory).getMapper(OutboxMapper.class);
    }
}
