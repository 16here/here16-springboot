package com.mall.config;

import com.mall.common.SensitiveUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 敏感字段存量数据迁移器（Phase 1 安全加固）。
 *
 * <p>手机号加密上线前，数据库里已有历史明文手机号。
 * 本组件在应用启动完成后自动扫描并加密回写：
 * <ul>
 *   <li>{@code users.phone}：用户表手机号；</li>
 *   <li>{@code addresses.receiver_phone}：收货地址手机号。</li>
 * </ul>
 *
 * <p>判定"待迁移"的标准：非空且不以 {@code enc:v1:} 前缀开头（明文）。
 * 迁移是幂等的：第二次启动时这些行都已是密文，不会再处理。
 *
 * <p>为什么用启动迁移而非一次性 SQL：
 * AES 加密必须在应用内用密钥完成，SQL 无法做；启动迁移自动执行，
 * 部署后无需手动跑脚本，且加密逻辑与业务代码共用同一把密钥。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensitiveDataMigrator implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final SensitiveUtil sensitiveUtil;

    @Override
    public void run(ApplicationArguments args) {
        migrateUsers();
        migrateAddresses();
    }

    /** 迁移 users.phone：逐个加密回写（行数少，逐行处理清晰安全） */
    private void migrateUsers() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, phone FROM users WHERE phone IS NOT NULL AND phone <> ''");
        int migrated = 0;
        for (Map<String, Object> row : rows) {
            String phone = (String) row.get("phone");
            if (phone != null && !phone.startsWith("enc:v1:")) {
                jdbcTemplate.update("UPDATE users SET phone = ? WHERE id = ?",
                        sensitiveUtil.encrypt(phone), row.get("id"));
                migrated++;
            }
        }
        if (migrated > 0) {
            log.info("敏感字段迁移完成: users.phone 加密 {} 条", migrated);
        }
    }

    /** 迁移 addresses.receiver_phone */
    private void migrateAddresses() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, receiver_phone FROM addresses WHERE receiver_phone IS NOT NULL AND receiver_phone <> ''");
        int migrated = 0;
        for (Map<String, Object> row : rows) {
            String phone = (String) row.get("receiver_phone");
            if (phone != null && !phone.startsWith("enc:v1:")) {
                jdbcTemplate.update("UPDATE addresses SET receiver_phone = ? WHERE id = ?",
                        sensitiveUtil.encrypt(phone), row.get("id"));
                migrated++;
            }
        }
        if (migrated > 0) {
            log.info("敏感字段迁移完成: addresses.receiver_phone 加密 {} 条", migrated);
        }
    }
}
