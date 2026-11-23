package com.familyhome.ai.service;

/**
 * 模型调用最终失败（所有候选都试过了）。
 *
 * <p>与 {@link IllegalStateException} 的区别在于它带了一个 {@code credential} 标记：
 * 401 说明 key 本身无效，换模型没有任何意义，提示要指向「去更新密钥」；
 * 其余情况（403 无权限 / 404 模型不存在 / 429 限流 / 5xx）都可能是单个模型的问题，
 * 提示应指向「模型不可用」而不是让管理员白换一次 key。
 */
public class ModelCallException extends RuntimeException {

    private final boolean credential;

    public ModelCallException(String message, boolean credential) {
        super(message);
        this.credential = credential;
    }

    public boolean credential() {
        return credential;
    }
}
