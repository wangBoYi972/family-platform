/**
 * 冒烟脚本的测试账号来源。
 *
 * <p>本地口令由 user-svc 的 {@code LocalBootstrap} 在空库时写入，管理员默认口令是文档里公开的开发值，
 * 但普通成员是运行时注册的、每台机器不同，所以一律走环境变量，避免把本机口令提交进仓库。
 *
 * <pre>
 * FAMILY_ADMIN_PHONE / FAMILY_ADMIN_PASSWORD
 * FAMILY_MEMBER_PHONE / FAMILY_MEMBER_PASSWORD
 * FAMILY_MEMBER2_PHONE / FAMILY_MEMBER2_PASSWORD   # 多人通话脚本的第三位成员，缺省复用成员一的口令
 * </pre>
 */

const DEFAULTS = {
  admin: { env: "FAMILY_ADMIN", phone: "19900000001", password: "local-admin-123456" },
  member: { env: "FAMILY_MEMBER", phone: "19900000002", password: "" },
  member2: { env: "FAMILY_MEMBER2", phone: "19900000003", password: "" }
};

/**
 * 读取某个角色的登录凭据，缺失口令时抛出可直接照做的提示（而不是发一个必然 401 的请求）。
 */
export function credentials(kind) {
  const spec = DEFAULTS[kind];
  if (!spec) throw new Error("未知账号类型: " + kind);
  const phone = process.env[spec.env + "_PHONE"] || spec.phone;
  const password =
    process.env[spec.env + "_PASSWORD"] ||
    (kind === "member2" ? process.env.FAMILY_MEMBER_PASSWORD || "" : spec.password);
  if (!password) {
    throw new Error(
      `缺少 ${spec.env}_PASSWORD：本地成员口令由注册接口生成，请先注册该成员或把已有口令注入环境变量`
    );
  }
  return { phone, password };
}

/** 脚本启动时把缺失项一次性说清楚，避免跑到一半才 401。 */
export function requireAccounts(...kinds) {
  return kinds.map(credentials);
}
