package com.mall.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感字段加解密工具（Phase 1 安全加固）。
 *
 * <p>用于手机号等敏感字段的"加密存储、解密展示"：
 * 数据库里只存密文（形如 {@code enc:v1:<base64>}），落库即不可读，
 * 即使数据库泄露也不会直接暴露用户手机号。
 *
 * <p><b>确定性加密设计（重要）</b>：
 * 手机号需要支持"等值查询"（登录时按手机号精确查找、注册时查重），
 * 所以 IV 由明文哈希派生（同一明文 → 同一密文），保证 WHERE phone = #{密文} 能命中。
 * 这是"可查询加密"的简化实现；生产环境如需更强安全性可升级 AES-SIV 或
 * 单独的密文索引列（本工具已预留格式前缀 {@code enc:v1:} 便于平滑迁移）。
 *
 * <p>密钥：来自配置 {@code security.sensitive-key}（32 字节 Base64），
 * 生产环境务必更换为随机生成的密钥并妥善保管（丢失后无法解密存量数据）。
 */
@Slf4j
@Component
public class SensitiveUtil {

    /** 密文格式前缀：识别"已加密"与迁移期"历史明文" */
    private static final String PREFIX = "enc:v1:";
    /** GCM 认证标签长度（位） */
    private static final int TAG_BITS = 128;
    /** GCM 推荐的 IV 长度（字节） */
    private static final int IV_LEN = 12;

    private final SecretKeySpec key;

    /**
     * 构造器：从配置读取 32 字节 Base64 密钥并解析。
     * 配置缺失/非法时抛异常——安全组件宁可直接失败，也不允许静默降级为明文。
     */
    public SensitiveUtil(@Value("${security.sensitive-key}") String base64Key) {
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != 32) {
            throw new IllegalStateException("security.sensitive-key 必须是 32 字节（Base64 编码）");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /**
     * 加密：明文 → 密文。空值直接返回；已加密的值幂等返回（防重复加密）。
     */
    public String encrypt(String plain) {
        if (!StringUtils.hasText(plain)) {
            return plain;
        }
        if (plain.startsWith(PREFIX)) {
            return plain; // 已是密文，避免二次加密
        }
        try {
            // 确定性 IV：IV = SHA-256(明文) 前 12 字节
            // （同一明文得到同一密文，从而支持数据库等值查询；代价是相等性可被观察）
            byte[] iv = sha256(plain.getBytes(StandardCharsets.UTF_8));
            byte[] iv12 = new byte[IV_LEN];
            System.arraycopy(iv, 0, iv12, 0, IV_LEN);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv12));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            // 密文载荷 = IV + 密文（解密时按 IV_LEN 切分）
            byte[] payload = new byte[IV_LEN + ct.length];
            System.arraycopy(iv12, 0, payload, 0, IV_LEN);
            System.arraycopy(ct, 0, payload, IV_LEN, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            // 加密失败必须抛出：静默失败会把明文落库，等于安全组件失效
            throw new IllegalStateException("敏感字段加密失败", e);
        }
    }

    /**
     * 解密：密文 → 明文。空值直接返回；迁移期历史明文（无前缀）原样返回。
     */
    public String decrypt(String cipher) {
        if (!StringUtils.hasText(cipher)) {
            return cipher;
        }
        if (!cipher.startsWith(PREFIX)) {
            return cipher; // 历史明文（迁移器启动后应已全部加密，此为兜底兼容）
        }
        try {
            byte[] payload = Base64.getDecoder().decode(cipher.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LEN];
            byte[] ct = new byte[payload.length - IV_LEN];
            System.arraycopy(payload, 0, iv, 0, IV_LEN);
            System.arraycopy(payload, IV_LEN, ct, 0, ct.length);

            Cipher cipherCipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipherCipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipherCipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 解密失败（密钥更换/数据损坏）：返回原文并告警，避免把用户数据卡死
            log.error("敏感字段解密失败，返回原文（请检查密钥是否与加密时一致）", e);
            return cipher;
        }
    }

    /** 是否为加密存储的值（迁移器判断用） */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** SHA-256 摘要（确定性 IV 的种子） */
    private static byte[] sha256(byte[] input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(input);
    }
}
