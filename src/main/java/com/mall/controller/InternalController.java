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
     * 查询用户最近一笔订单（GET /api/internal/orders/recent?userId=）。
     *
     * <p>用户问"我的快递到哪了"但没提供订单号时，Python 工具先调本接口拿到
     * 最近订单再查物流；用户没有任何订单时返回 data=null，AI 会引导提供订单号。
     * 注意：/orders/recent 是精确路径，优先级高于 /orders/{orderId}，不会误匹配。
     */
    @GetMapping("/orders/recent")
    public Result<OrderVO> recentOrder(@RequestParam Long userId) {
        return Result.success(orderService.recent(userId));
    }

    /**
     * 按订单号查询订单状态（GET /api/internal/orders/by-no/{orderNo}?userId=）。
     *
     * <p>用户聊天里发的是 19 位订单号（order_no），不是数据库主键 id；
     * Python 工具提取到订单号后调本接口（精确路径，优先级高于 /orders/{orderId}），
     * 服务层带 userId 归属校验，查不到返回 NOT_FOUND。
     */
    @GetMapping("/orders/by-no/{orderNo}")
    public Result<OrderVO> orderByNo(@PathVariable String orderNo, @RequestParam Long userId) {
        return Result.success(orderService.detailByOrderNo(userId, orderNo));
    }

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
