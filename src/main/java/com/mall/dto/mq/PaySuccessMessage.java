package com.mall.dto.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 支付成功通知消息体（Phase 2 异步化）。
 *
 * <p>支付回调主线程只做"验签 + 更新流水 + 订单状态"这些强一致性动作，
 * 把"记录购买行为、发送通知"这类可异步的收尾工作发到消息里，
 * 由消费者异步完成——回调响应更快，支付平台不再等我们做完整套动作。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaySuccessMessage {

    /** 订单 ID */
    private Long orderId;

    /** 用户 ID */
    private Long userId;

    /** 支付流水号（日志/通知文案用） */
    private String paymentNo;

    /** 支付方式（ALIPAY/WECHAT/MOCK） */
    private String payMethod;
}
