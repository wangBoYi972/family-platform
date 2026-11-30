package com.familyhome.user.model;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.*;
@Data
@TableName("family_user")
public class FamilyUser {
  @TableId(type=IdType.AUTO) private Long id; private String phone; private String displayName; private LocalDateTime createdAt;
  /** 小程序 openid：订阅消息推送给谁全靠它，wx.login 换取后绑定。 */
  private String wxOpenid;
}

