package com.mall.common;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单号生成器（Phase 3 分表后升级：新增雪花 ID 用于订单表主键）。
 *
 * <p>分表后订单主键不能再用数据库自增：4 张分表各自 AUTO_INCREMENT 会重复
 * （orders_0 和 orders_1 都可能出现 id=1001），全局唯一主键必须由应用生成。
 * 本类提供两种产物：
 * <ul>
 *   <li>{@link #generate(Long)}：业务订单号 order_no（22 位可读字符串，
 *       时间戳 + 用户ID + 随机数，原有逻辑保留——订单号要给人看、按时间排）；</li>
 *   <li>{@link #generateOrderId()}：主键 id（雪花 ID 64 位 Long，
 *       全局唯一、趋势递增、纯数值），分表 INSERT 前调用。</li>
 * </ul>
 *
 * <p>雪花 ID 结构（64 位）：
 * 1 位符号位(0) + 41 位毫秒时间戳(约可用 69 年) + 10 位机器/进程位 + 12 位同毫秒序列号(4096/ms)。
 * 单机部署 workerId 固定 0；多机部署可通过配置区分 workerId 防止跨实例碰撞。
 */
@Component
public class OrderNoGenerator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 雪花 ID 起始纪元：2026-01-01 00:00:00（毫秒），保证 41 位时间戳用满未来空间 */
    private static final long EPOCH_MILLIS = 1767225600000L;

    /** 机器/进程位偏移与掩码（10 位，本机单实例固定 0，可配置） */
    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    private static final long WORKER_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;

    /** workerId（默认 0；多实例部署时通过构造参数/配置注入，避免跨实例主键冲突） */
    private final long workerId = 0L;

    /** 同一毫秒内的递增序号（线程安全） */
    private final AtomicLong sequence = new AtomicLong(0L);

    /** 最近一次使用的时间戳（毫秒） */
    private volatile long lastTimestamp = -1L;

    /**
     * 生成订单号（22 位字符串，业务可读、按时间排序）。
     */
    public String generate(Long userId) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String userPart = String.format("%04d", userId == null ? 0 : userId % 10000);
        String randomPart = String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
        return timestamp + userPart + randomPart;
    }

    /**
     * 生成雪花 ID（订单表主键，全局唯一 + 趋势递增）。
     *
     * <p>实现要点：
     * <ul>
     *   <li>同一毫秒内通过 AtomicLong 递增序列号（0~4095），超过则自旋等待下一毫秒；</li>
     *   <li>时序回拨（时钟倒退）时拒绝生成（抛异常），防止生成重复 ID——演示环境时钟 NTP 同步，触发概率极低；</li>
     *   <li>趋势递增：时间戳放高位，索引友好（B+ 树顺序插入，减少页分裂）。</li>
     * </ul>
     */
    public synchronized long generateOrderId() {
        long timestamp = System.currentTimeMillis() - EPOCH_MILLIS;
        long current = lastTimestamp;

        if (timestamp < current) {
            // 时钟回拨：拒绝生成（宁可抛错也不产生重复主键）
            throw new IllegalStateException("系统时钟回拨，拒绝生成订单ID: now=" + timestamp + ", last=" + current);
        }
        if (timestamp == current) {
            long seq = sequence.incrementAndGet() & MAX_SEQUENCE;
            if (seq == 0) {
                // 本毫秒序号用尽（4096 单），自旋等待下一毫秒
                while (timestamp <= current) {
                    timestamp = System.currentTimeMillis() - EPOCH_MILLIS;
                }
            }
        } else {
            sequence.set(0L);
        }
        lastTimestamp = timestamp;
        return (timestamp << TIMESTAMP_SHIFT) | (workerId << WORKER_SHIFT) | sequence.get();
    }
}
