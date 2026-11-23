package com.familyhome.iot.config;

import com.familyhome.iot.util.TokenEncryptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * IoT 配置
 */
@Configuration
public class IotConfig {

  @Value("${family.iot.encryption-key:}")
  private String encryptionKey;

  @Value("${family.iot.ws-timeout:10}")
  private int wsTimeout;

  @Value("${family.iot.command-timeout:5}")
  private int commandTimeout;

  @Bean
  public TokenEncryptor tokenEncryptor() {
    return new TokenEncryptor(encryptionKey);
  }

  public int getWsTimeout() {
    return wsTimeout;
  }

  public int getCommandTimeout() {
    return commandTimeout;
  }
}
