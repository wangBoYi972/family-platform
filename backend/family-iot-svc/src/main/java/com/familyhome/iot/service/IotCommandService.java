package com.familyhome.iot.service;

import com.familyhome.iot.dto.IotCommandDTO;

/**
 * 命令执行服务
 */
public interface IotCommandService {

  /**
   * 执行开关或灯光命令
   *
   * @param householdId 家庭编号
   * @param userId 用户编号
   * @param entityId 目标实体（如 light.living_room）
   * @param dto 命令参数
   * @throws Exception 命令执行失败
   */
  void executeCommand(Long householdId, Long userId, String entityId, IotCommandDTO dto) throws Exception;

  /**
   * 触发场景
   *
   * @param householdId 家庭编号
   * @param userId 用户编号
   * @param sceneEntityId 场景实体
   * @throws Exception 触发失败
   */
  void activateScene(Long householdId, Long userId, String sceneEntityId) throws Exception;
}
