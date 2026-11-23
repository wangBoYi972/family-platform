package com.familyhome.iot.util;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 令牌加密工具
 *
 * 使用 AES-128-CBC 加密 Home Assistant 访问令牌
 */
public class TokenEncryptor {

  private static final String ALGORITHM = "AES";
  private static final String CIPHER_ALGORITHM = "AES/CBC/PKCS5Padding";
  private static final int IV_SIZE = 16;
  private final byte[] key;

  public TokenEncryptor(String encryptionKey) {
    // 使用提供的密钥，转换为 128 位
    byte[] decodedKey = Base64.getDecoder().decode(encryptionKey);
    // 如果密钥长度不足，补充；如果超过，截断
    this.key = new byte[16];
    System.arraycopy(decodedKey, 0, this.key, 0, Math.min(16, decodedKey.length));
  }

  /**
   * 加密令牌
   * 返回格式：Base64(IV + EncryptedData)
   */
  public String encrypt(String token) throws Exception {
    // 生成随机 IV
    SecureRandom random = new SecureRandom();
    byte[] iv = new byte[IV_SIZE];
    random.nextBytes(iv);

    SecretKeySpec secretKey = new SecretKeySpec(key, 0, key.length, ALGORITHM);
    IvParameterSpec ivSpec = new IvParameterSpec(iv);
    Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);

    byte[] encryptedData = cipher.doFinal(token.getBytes());

    // 返回 IV + EncryptedData 的 Base64 编码
    byte[] result = new byte[iv.length + encryptedData.length];
    System.arraycopy(iv, 0, result, 0, iv.length);
    System.arraycopy(encryptedData, 0, result, iv.length, encryptedData.length);
    return Base64.getEncoder().encodeToString(result);
  }

  /**
   * 解密令牌
   * 输入格式：Base64(IV + EncryptedData)
   */
  public String decrypt(String encryptedToken) throws Exception {
    byte[] decodedData = Base64.getDecoder().decode(encryptedToken);

    // 分离 IV 和加密数据
    byte[] iv = new byte[IV_SIZE];
    byte[] encrypted = new byte[decodedData.length - IV_SIZE];
    System.arraycopy(decodedData, 0, iv, 0, IV_SIZE);
    System.arraycopy(decodedData, IV_SIZE, encrypted, 0, encrypted.length);

    SecretKeySpec secretKey = new SecretKeySpec(key, 0, key.length, ALGORITHM);
    IvParameterSpec ivSpec = new IvParameterSpec(iv);
    Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
    cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);

    byte[] decryptedData = cipher.doFinal(encrypted);
    return new String(decryptedData);
  }
}

