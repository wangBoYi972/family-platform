package com.familyhome.iot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * IoT 服务启动类
 */
@SpringBootApplication(scanBasePackages = {"com.familyhome.iot", "com.familyhome.security", "com.familyhome.common"})
@EnableDiscoveryClient
public class FamilyIotApplication {

  public static void main(String[] args) {
    SpringApplication.run(FamilyIotApplication.class, args);
  }
}
