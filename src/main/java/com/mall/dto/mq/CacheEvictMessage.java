package com.mall.dto.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 缓存延迟删除消息体（Phase 2 双删消息化）。
 *
 * <p>写操作（改商品/分类）会"立即删缓存 + 延迟再删一次"，
 * 第二次删除原来用进程内定时线程（JVM 一重启就丢、多实例不共享），
 * 现在改为发一条 RocketMQ 延迟消息（延迟 1 秒），
 * 由消费者在延迟到点后删除 Redis key——broker 持久化，进程重启也不丢。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheEvictMessage {

    /** 要删除的 Redis key（如 cache:product:detail:12） */
    private String redisKey;
}
