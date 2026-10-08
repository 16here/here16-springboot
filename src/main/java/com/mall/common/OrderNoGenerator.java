package com.mall.common;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单号生成器。
 *
 * <p>订单号格式：时间戳(14 位) + 用户 ID(4 位，左补零) + 随机数(4 位)，共 22 位。
 * 例如：20260929102015 + 0001 + 4821。
 *
 * <p>为什么这样设计：
 * <ul>
 *   <li>时间戳部分让订单号天然按时间排序、可读（从订单号能看出下单时间）；</li>
 *   <li>用户 ID 降低不同用户订单号碰撞概率；</li>
 *   <li>随机 4 位兜底防同一用户同一秒下多单撞号；</li>
 *   <li>数据库 order_no 唯一索引做最终兜底（极端碰撞时重新生成重试）。</li>
 * </ul>
 */
@Component
public class OrderNoGenerator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 生成订单号 */
    public String generate(Long userId) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String userPart = String.format("%04d", userId == null ? 0 : userId % 10000);
        String randomPart = String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
        return timestamp + userPart + randomPart;
    }
}
