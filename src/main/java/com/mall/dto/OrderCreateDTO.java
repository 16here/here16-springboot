package com.mall.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建订单请求参数。
 *
 * <p>安全说明：前端只传"地址 ID + 勾选的购物车项 ID 列表 + 备注"。
 * 商品、规格、价格、库存全部由后端从购物车与商品表读取——
 * 前端无法篡改成交价，价格以服务端快照为准。
 */
@Data
public class OrderCreateDTO {

    /** 收货地址 ID（必须是当前用户的地址） */
    @NotNull(message = "请选择收货地址")
    private Long addressId;

    /** 勾选结算的购物车项 ID 列表（至少 1 个） */
    @NotEmpty(message = "请选择要结算的商品")
    private List<Long> cartIds;

    /** 买家备注（可选，最长 200 字） */
    @Size(max = 200, message = "备注最长 200 字")
    private String remark;

    /** 使用的用户优惠券 ID（user_coupons.id，阶段 8；不传 = 不使用优惠券） */
    private Long userCouponId;

    /**
     * 幂等键（Phase 1）：前端每次"提交订单"生成一次 UUID 并携带。
     * 后端以 userId + requestId 做 Redis 占位去重，防止双击/重试重复下单。
     * 可空（兼容旧前端）；为空时后端不启用幂等检查。
     */
    private String requestId;
}
