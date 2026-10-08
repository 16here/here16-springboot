package com.mall.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 下单时使用优惠券的入参（阶段 8）。
 *
 * <p>由 OrderCreateDTO.userCouponId 传入：用户从"可用券"里选一张，
 * 后端校验归属/状态/门槛后锁定，支付成功才核销。
 */
@Data
public class CouponUseDTO {

    /** 用户优惠券 ID（user_coupons.id），必须是当前登录用户且未使用的券 */
    @NotNull(message = "请选择要使用的优惠券")
    private Long userCouponId;
}
