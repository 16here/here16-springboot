package com.mall.dto.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户行为消息体（Phase 2 异步埋点）。
 *
 * <p>浏览商品/收藏/加购/购买时，主流程不再同步写 user_behaviors 表，
 * 而是把这条行为打包成消息发给 RocketMQ，由消费者异步落库。
 * 这样主接口（商品详情、加购）响应更快，埋点也不拖累业务。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BehaviorMessage {

    /** 用户 ID（游客浏览为 null，只参与热度统计） */
    private Long userId;

    /** 商品 ID */
    private Long productId;

    /** 规格 ID（浏览可空） */
    private Long skuId;

    /** 行为类型：VIEW/FAVORITE/CART/PURCHASE（见 UserBehaviorRepository 常量） */
    private String type;
}
