package com.mall.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 月分表自动建表器（Phase 3 分表）。
 *
 * <p>行为/库存流水按月分表，跨月写入时目标表（如 user_behaviors_202611）
 * 还不存在。本组件负责在<b>写入前</b>自动补建当月分表：
 * <code>CREATE TABLE user_behaviors_202611 LIKE user_behaviors_202610</code>
 * （以已存在的最近一张分表为模板，克隆表结构 + 索引）。
 *
 * <p>为什么必须自动建表：人工建表会漏（换月忘记建 → 写入直接报错），
 * 定时建表有窗口期；写入前按需建表最稳，且用本地集合缓存"已确认存在"
 * 的月份，避免每笔写都查一次 information_schema。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShardingTableManager {

    /** 动态数据源（建表属于 DDL，必须走主库） */
    private final DataSource dynamicDataSource;

    /** 已确认存在（或已尝试建过）的表，避免重复查询/重复 DDL */
    private final ConcurrentMap<String, Boolean> ensured = new ConcurrentHashMap<>();

    /**
     * 确保月分表存在：不存在则用"最近一张已存在的分表"克隆建表。
     *
     * <p>幂等逻辑（重要）：
     * <ol>
     *   <li>本地缓存命中 → 直接返回（每笔写只查一次库，零开销）；</li>
     *   <li>查 information_schema：目标表已存在 → 标记缓存返回，<b>绝不执行 DDL</b>；
     *       否则 {@code CREATE TABLE t LIKE 模板} 会把模板选成 t 自己，
     *       触发 MySQL 1066（Not unique table/alias）；</li>
     *   <li>模板表也没有 → 跳过自动建表（首次部署未执行 sharding_ddl.sql 时），
     *       由人工补执行，仅记告警避免反复刷屏。</li>
     * </ol>
     * <p>建表用 {@link #dynamicDataSource} 的<b>独立连接</b>执行 DDL：
     * MySQL 的 DDL 隐式提交只影响执行 DDL 的连接上的事务，
     * 独立连接没有业务事务，因此不会把调用方（如下单 @Transactional）
     * 的事务提前提交。
     *
     * @param baseName 基础表名（user_behaviors / inventory_logs）
     * @param month    yyyyMM（如 202611）
     */
    public void ensureMonthTable(String baseName, String month) {
        String table = baseName + "_" + month;
        if (Boolean.TRUE.equals(ensured.get(table))) {
            return;
        }
        synchronized (this) {
            if (Boolean.TRUE.equals(ensured.get(table))) {
                return;
            }
            // 表已存在：标记缓存并返回，不要执行 CREATE LIKE（模板会选到它自己 → 1066）
            if (tableExists(table)) {
                ensured.put(table, Boolean.TRUE);
                return;
            }
            String template = findTemplate(baseName, table);
            if (template == null) {
                // 模板表都没有（首次部署分表 DDL 未执行）——放弃自动建表，由人工执行 sharding_ddl.sql
                log.warn("月分表模板不存在，跳过自动建表: base={}, month={}", baseName, month);
                ensured.put(table, Boolean.TRUE); // 避免反复告警
                return;
            }
            try (Connection conn = dynamicDataSource.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS `" + table + "` LIKE `" + template + "`");
                ensured.put(table, Boolean.TRUE);
                log.info("自动创建月分表完成: {} (模板 {})", table, template);
            } catch (Exception e) {
                // 建表失败不能抛给业务（业务写入前仍有唯一索引等防护）；
                // 但本次写入大概率失败，记录日志便于排障
                log.error("自动创建月分表失败: table={}, err={}", table, e.getMessage());
            }
        }
    }

    /** 目标表是否已存在（information_schema 查询，走主库独立连接） */
    private boolean tableExists(String table) {
        try (Connection conn = dynamicDataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM information_schema.TABLES " +
                             "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "'")) {
            return rs.next();
        } catch (Exception e) {
            // 查询失败按"存在"处理：宁可不建表也不重复 DDL（写入失败有业务兜底日志）
            log.warn("查询月分表是否存在失败: table={}, err={}", table, e.getMessage());
            return true;
        }
    }

    /**
     * 查找 baseName 最近一张已存在的分表（information_schema 按表名倒序，
     * yyyyMM 字符串排序 = 时间排序，所以倒序取最新）。
     *
     * <p>必须排除目标表自身（{@code <> 目标表}）：目标表已存在时如果选到它，
     * {@code CREATE TABLE t LIKE t} 会触发 MySQL 1066。
     */
    private String findTemplate(String baseName, String excludeTable) {
        try (Connection conn = dynamicDataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT TABLE_NAME FROM information_schema.TABLES " +
                             "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE '" + baseName + "\\_%' " +
                             "AND TABLE_NAME <> '" + excludeTable + "' " +
                             "ORDER BY TABLE_NAME DESC LIMIT 1")) {
            if (rs.next()) {
                return rs.getString(1);
            }
        } catch (Exception e) {
            log.warn("查询分表模板失败: base={}, err={}", baseName, e.getMessage());
        }
        return null;
    }
}
