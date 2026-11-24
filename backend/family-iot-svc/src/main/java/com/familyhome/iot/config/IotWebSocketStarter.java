package com.familyhome.iot.config;

import com.familyhome.iot.service.IotHubService;
import com.familyhome.iot.service.IotWebSocketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时为所有已绑定真实中枢的家庭建立 WebSocket 实时连接。
 *
 * <p>此前 IotWebSocketService 的实现完整却从未被调用，设备状态只能靠前端 5 秒轮询。
 * 本启动器把实时链路接上：HA 的 state_changed 事件 → 更新设备快照表 → 前端下次轮询即可读到最新值。
 *
 * <p>连接全部异步进行，任何家庭连接失败都只记日志，不影响服务启动，也不影响其它家庭。
 */
@Slf4j
@Component
public class IotWebSocketStarter implements ApplicationRunner {

  @Autowired
  private IotHubService hubService;

  @Autowired
  private IotWebSocketService wsService;

  @Autowired(required = false)
  private TaskExecutor taskExecutor;

  @Override
  public void run(ApplicationArguments args) {
    List<Long> households;
    try {
      households = hubService.listBoundHouseholdIds();
    } catch (Exception e) {
      log.warn("读取已绑定中枢家庭列表失败，跳过实时连接初始化", e);
      return;
    }
    log.info("开始初始化 {} 个家庭的中枢实时连接", households.size());
    for (Long householdId : households) {
      connectAsync(householdId);
    }
  }

  private void connectAsync(Long householdId) {
    Runnable task = () -> {
      try {
        wsService.connect(householdId);
      } catch (Exception e) {
        // 中枢离线/网络不通是常态，不能让启动失败
        log.warn("家庭 {} 的中枢实时连接未建立（将依赖轮询兜底）: {}", householdId, e.getMessage());
      }
    };
    if (taskExecutor != null) {
      taskExecutor.execute(task);
    } else {
      // 无自定义执行器时用守护线程，避免阻塞启动或阻止 JVM 退出
      Thread t = new Thread(task, "iot-ws-connect-" + householdId);
      t.setDaemon(true);
      t.start();
    }
  }
}
