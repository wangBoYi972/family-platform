package com.familyhome.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.retry.interceptor.RetryInterceptorBuilder;

@Configuration
@ConditionalOnProperty(name = "family.messaging.enabled", havingValue = "true")
@EnableConfigurationProperties(MessagingProperties.class)
@Import({OutboxPublisher.class, RedisTicketStore.class, RedisIdempotencyLock.class, RedisOnlineConnectionRegistry.class})
public class MessagingRabbitConfiguration {
    @Bean Jackson2JsonMessageConverter rabbitMessageConverter() { return new Jackson2JsonMessageConverter(); }

    @Bean TopicExchange familyEventExchange(MessagingProperties p) { return new TopicExchange(p.exchange(), true, false); }

    @Bean DirectExchange familyDeadLetterExchange(MessagingProperties p) { return new DirectExchange(p.exchange() + ".dlx", true, false); }

    @Bean Queue familyEventQueue(MessagingProperties p) {
        return QueueBuilder.durable(p.queue()).deadLetterExchange(p.exchange() + ".dlx").deadLetterRoutingKey(p.queue() + ".dead").build();
    }

    @Bean Queue familyDeadLetterQueue(MessagingProperties p) { return QueueBuilder.durable(p.queue() + ".dead").build(); }

    @Bean Binding familyEventBinding(Queue familyEventQueue, TopicExchange familyEventExchange) { return BindingBuilder.bind(familyEventQueue).to(familyEventExchange).with("#"); }

    @Bean Binding familyDeadLetterBinding(Queue familyDeadLetterQueue, DirectExchange familyDeadLetterExchange, MessagingProperties p) { return BindingBuilder.bind(familyDeadLetterQueue).to(familyDeadLetterExchange).with(p.queue() + ".dead"); }

    @Bean SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory, Jackson2JsonMessageConverter converter) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        factory.setDefaultRequeueRejected(false);
        factory.setErrorHandler(new ConditionalRejectingErrorHandler());
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().maxAttempts(3).backOffOptions(1000, 2.0, 10000).recoverer((message, cause) -> { throw new IllegalStateException("消息重试耗尽", cause); }).build());
        return factory;
    }
}
