package com.mall.controller;

import com.mall.common.Result;
import com.mall.dto.AfterSaleCreateDTO;
import com.mall.dto.AfterSaleVO;
import com.mall.dto.OrderVO;
import com.mall.dto.ShippingVO;
import com.mall.service.AfterSaleService;
import com.mall.service.OrderService;
import com.mall.service.ShippingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部接口（/api/internal/**，阶段 11.4）：供 Python AI 服务（Function Calling 工具）调用。
 *
 * <p>与用户端接口的区别：
 * <ul>
 *   <li>鉴权方式不同：不走用户 JWT，而是 {@link InternalAuthInterceptor}
 *       （X-Internal-Token 令牌 + IP 白名单），见 SecurityConfig 放行规则；</li>
 *   <li>userId 由调用方（AI 服务）显式传入：AI 从对话 State.user_info 拿到
 *       用户 ID 后随请求提交，归属校验（只能查/操作自己的订单）仍在服务层生效，
 *       避免 AI 服务越权读取他人订单；</li>
 *   <li>只暴露 AI 客服需要的只读查询与售后创建，不暴露任何写敏感数据能力
 *       （订单状态流转/退款审批仍在管理端）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class InternalController {

    private final OrderService orderService;
    private final ShippingService shippingService;
    private final AfterSaleService afterSaleService;

    /**
     * 查询订单状态（GET /api/internal/orders/{orderId}?userId=）。
     *
     * <p>Python 工具 query_order_status 调用：返回 OrderVO（状态/金额/订单号/商品明细）。
     */
    @GetMapping("/orders/{orderId}")
    public Result<OrderVO> orderStatus(@PathVariable Long orderId, @RequestParam Long userId) {
        return Result.success(orderService.detail(userId, orderId));
    }

    /**
     * 查询物流轨迹（GET /api/internal/logistics/{orderId}?userId=）。
     *
     * <p>Python 工具 query_logistics 调用：返回 ShippingVO（快递公司/单号/轨迹时间轴）。
     */
    @GetMapping("/logistics/{orderId}")
    public Result<ShippingVO> logistics(@PathVariable Long orderId, @RequestParam Long userId) {
        return Result.success(shippingService.getByOrder(userId, orderId));
    }

    /**
     * 创建售后申请（POST /api/internal/aftersales?userId= + AfterSaleCreateDTO）。
     *
     * <p>Python 工具 request_refund / 退换货技能调用：校验链（订单归属、状态可售后、
     * 金额 ≤ 实付、无进行中售后）与用户端 /api/after-sales 完全一致。
     */
    @PostMapping("/aftersales")
    public Result<AfterSaleVO> createAfterSale(@RequestParam Long userId,
                                               @Valid @RequestBody AfterSaleCreateDTO dto) {
        return Result.success(afterSaleService.apply(userId, dto));
    }
}
