package com.mall.service;

import com.mall.annotation.DS;
import com.mall.common.BusinessException;
import com.mall.common.OrderNoGenerator;
import com.mall.common.PageResult;
import com.mall.common.SensitiveUtil;
import com.mall.common.ShardingRouter;
import com.mall.dto.OrderCreateDTO;
import com.mall.dto.OrderItemVO;
import com.mall.dto.OrderQueryDTO;
import com.mall.dto.OrderVO;
import com.mall.entity.Address;
import com.mall.entity.Cart;
import com.mall.entity.Order;
import com.mall.entity.OrderItem;
import com.mall.entity.OrderStatusLog;
import com.mall.enums.OrderEvent;
import com.mall.enums.OrderStatusEnum;
import com.mall.repository.AddressRepository;
import com.mall.repository.CartRepository;
import com.mall.repository.OrderItemRepository;
import com.mall.repository.OrderRepository;
import com.mall.repository.OrderStatusLogRepository;
import com.mall.repository.ProductRepository;
import com.mall.repository.ProductSkuRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单服务：创建（校验地址 → 购物车 → 可卖库存 → 订单号 → 明细快照 → 地址快照 →
 * 锁定库存 → 清购物车）、列表、详情、状态流转（取消/确认收货/发货/支付/超时/关闭）、
 * 管理端列表与发货。
 *
 * <p>关键设计（阶段 3 起延续，阶段 5/6 强化）：
 * <ul>
 *   <li><b>价格以服务端为准</b>：前端只传 cartIds + addressId，商品/规格/单价全部由后端
 *       从购物车与商品表读取，前端无法篡改成交价；</li>
 *   <li><b>快照</b>：订单与明细冗余存储商品名/图片/价格/收货地址，
 *       商品后续改名改价删除都不影响已成交订单；</li>
 *   <li><b>预占式库存（阶段 6）</b>：下单<b>锁定</b>可卖库存（locked_stock += qty，
 *       Redis 预减快速拦截 + 数据库条件 UPDATE 最终兜底）→ 支付成功<b>扣减</b>
 *       （stock/locked_stock 同步减少）→ 取消/关闭/超时<b>释放</b>锁定；
 *       每次变更写库存流水（LOCK/DEDUCT/RELEASE），详见 {@link InventoryService}；</li>
 *   <li><b>状态机（阶段 5）</b>：所有状态流转先经 {@link OrderStateMachine} 校验合法性，
 *       再由"条件 UPDATE + 状态前置"落库（并发安全），每次流转写一条
 *       {@link OrderStatusLog} 状态日志（用户时间轴 / 管理端追溯）；</li>
 *   <li><b>事务</b>：创建/取消/关闭/确认收货全程一个事务，任一环节失败全部回滚。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final AddressRepository addressRepository;
    private final CartRepository cartRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusLogRepository orderStatusLogRepository;
    private final OrderNoGenerator orderNoGenerator;
    private final OrderStateMachine orderStateMachine;
    private final InventoryService inventoryService;
    private final PromotionService promotionService;
    private final CouponService couponService;
    private final RecommendService recommendService;
    // Phase 1：下单幂等（Redis SETNX 占位，防双击/重试重复下单）
    private final IdempotentService idempotentService;
    /** Phase 2：RocketMQ 生产者（订单超时延迟消息 + 支付成功异步通知） */
    private final RocketMQProducerService rocketMQProducerService;

    /** 待付款订单超时分钟数（application.yml order.timeout-minutes，默认 30 分钟） */
    @org.springframework.beans.factory.annotation.Value("${order.timeout-minutes:30}")
    private int timeoutMinutes;
    // Phase 1：订单快照手机号加密存储（解密在 toVO 展示时进行）
    private final SensitiveUtil sensitiveUtil;

    /**
     * 创建订单（核心流程，阶段 8 起支持营销优惠）：
     * 校验地址 → 取勾选购物车项 → 校验商品与库存（命中秒杀则按秒杀价成交）→
     * 计算活动优惠（满减/折扣/秒杀）与优惠券优惠 → 生成订单号 →
     * 写订单（金额快照 + 地址快照）→ 锁优惠券 → 写明细（商品快照）→
     * 锁库存 → 清购物车。
     *
     * <p>金额口径（全部由后端计算，前端不可篡改）：
     * <pre>
     *   originalAmount = Σ 商品原价 × 数量
     *   promotionAmount = 秒杀优惠 + 满减/折扣优惠（活动自动参与）
     *   couponAmount = 优惠券减免（门槛基于原价合计，优惠作用于活动后金额）
     *   totalAmount = originalAmount - promotionAmount - couponAmount（最低 0）
     * </pre>
     */
    @Transactional
    public OrderVO create(Long userId, OrderCreateDTO dto) {
        // Phase 3 分表：本请求后续 SQL 按 userId 路由订单/明细分表（user_id % 4）。
        // 路由信息线程内生效，HTTP 请求结束由 ShardingContextFilter 统一清理。
        ShardingRouter.routeOrdersByUser(userId);
        // 0. 幂等检查（Phase 1）：同一 userId + requestId 只允许成功一次。
        //    前端每次提交生成新 UUID；重复请求（双击/重试）占位失败直接拒绝，
        //    不进入后续任何业务逻辑。TTL 5 分钟，超时后自动释放允许重新下单。
        if (StringUtils.hasText(dto.getRequestId())) {
            String idemKey = "order:user:" + userId + ":" + dto.getRequestId();
            if (!idempotentService.tryAcquire(idemKey, 300)) {
                log.warn("检测到重复下单请求: userId={}, requestId={}", userId, dto.getRequestId());
                throw new BusinessException(429, "订单已提交，请勿重复操作");
            }
        }

        // 1. 校验收货地址（必须属于当前用户）
        Address address = addressRepository.findByIdAndUserId(dto.getAddressId(), userId);
        if (address == null) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "收货地址不存在");
        }

        // 2. 取勾选的购物车项（按 cartIds 过滤，价格/库存以后端数据为准）
        List<Cart> carts = cartRepository.selectByUserId(userId);
        Map<Long, Cart> cartMap = new HashMap<>();
        for (Cart c : carts) {
            cartMap.put(c.getId(), c);
        }
        List<OrderItem> items = new ArrayList<>();
        List<Cart> orderedCarts = new ArrayList<>();
        BigDecimal originalAmount = BigDecimal.ZERO;
        for (Long cartId : dto.getCartIds()) {
            Cart cart = cartMap.get(cartId);
            if (cart == null) {
                throw new BusinessException(BusinessException.PARAM_ERROR, "购物车项不存在，请刷新后重试");
            }
            // 3. 库存校验（下单前的预校验，扣库存阶段还有条件更新兜底）
            int available = resolveStock(cart);
            if (available < cart.getQuantity()) {
                throw new BusinessException(BusinessException.PARAM_ERROR,
                        "「" + cart.getProductName() + "」库存不足");
            }
            // 4. 秒杀价替换（命中进行中的秒杀活动 → 按秒杀价成交，否则原价）
            BigDecimal price = resolvePrice(cart);
            BigDecimal flashPrice = promotionService.getFlashSalePrice(cart.getProductId(), cart.getSkuId());
            if (flashPrice != null && flashPrice.compareTo(price) < 0) {
                price = flashPrice;
            }
            // 5. 组装明细快照（price 为成交单价：秒杀价或原价）
            OrderItem item = new OrderItem();
            item.setProductId(cart.getProductId());
            item.setProductName(cart.getProductName());
            item.setProductImage(cart.getProductImage());
            item.setSkuId(cart.getSkuId());
            item.setSkuName(cart.getSkuName());
            item.setPrice(price);
            item.setQuantity(cart.getQuantity());
            items.add(item);
            orderedCarts.add(cart);
            originalAmount = originalAmount.add(resolvePrice(cart).multiply(BigDecimal.valueOf(cart.getQuantity())));
        }

        // 6. 计算活动优惠（秒杀 + 满减/折扣，自动参与）
        BigDecimal promotionAmount = promotionService.calc(orderedCarts).promotionAmount();
        BigDecimal afterPromotion = originalAmount.subtract(promotionAmount);
        if (afterPromotion.compareTo(BigDecimal.ZERO) < 0) {
            afterPromotion = BigDecimal.ZERO;
        }

        // 7. 优惠券：门槛基于原价合计，优惠作用于活动后金额；先算不落库，订单落库后锁券
        BigDecimal couponAmount = BigDecimal.ZERO;
        Long userCouponId = dto.getUserCouponId();
        if (userCouponId != null) {
            couponAmount = couponService.calcDiscount(userId, userCouponId, originalAmount, afterPromotion);
        }
        BigDecimal totalAmount = afterPromotion.subtract(couponAmount);
        if (totalAmount.compareTo(BigDecimal.ZERO) < 0) {
            totalAmount = BigDecimal.ZERO;
        }

        // 8. 生成订单号并落库（金额快照 + 地址快照）
        //    Phase 3 分表后：主键不用数据库自增（4 张分表各自自增会重复），
        //    改由应用生成雪花 ID（全局唯一、趋势递增、索引友好）。
        Order order = new Order();
        order.setId(orderNoGenerator.generateOrderId());
        order.setOrderNo(orderNoGenerator.generate(userId));
        order.setUserId(userId);
        order.setOriginalAmount(originalAmount);
        order.setPromotionAmount(promotionAmount);
        order.setCouponAmount(couponAmount);
        order.setCouponId(userCouponId);
        order.setTotalAmount(totalAmount);
        order.setStatus(OrderStatusEnum.UNPAID.getCode());
        order.setReceiverName(address.getReceiverName());
        // Phase 1：订单快照手机号加密存储（地址手机号本身已加密，快照直接沿用密文；
        // 展示时 toVO 统一解密）
        order.setReceiverPhone(address.getReceiverPhone());
        order.setReceiverAddress(address.getProvince() + address.getCity()
                + address.getDistrict() + address.getDetail());
        order.setRemark(dto.getRemark());
        orderRepository.insert(ShardingRouter.routedTableName(ShardingRouter.T_ORDERS), order);

        // 9. 锁优惠券（订单落库后绑定 orderId；条件 UPDATE，失败整体回滚）
        if (userCouponId != null) {
            couponService.lock(userId, userCouponId, order.getId());
        }

        // 10. 写订单明细（商品快照）
        for (OrderItem item : items) {
            item.setOrderId(order.getId());
        }
        orderItemRepository.insertBatch(ShardingRouter.routedTableName(ShardingRouter.T_ORDER_ITEMS), items);

        // 11. 锁定库存（阶段 6：预占式库存，不再直接扣减）
        //     Redis 预减（快速拦截超卖）→ 数据库条件锁定（最终防线）→ 写 LOCK 流水；
        //     任一步失败整体回滚（Redis 侧已由 InventoryService 回补）。
        inventoryService.lock(order.getId(), order.getOrderNo(), userId, items);

        // 12. 清掉已下单的购物车项
        for (Long cartId : dto.getCartIds()) {
            cartRepository.deleteByIdAndUserId(cartId, userId);
        }

        // 13. Phase 2：发送订单超时延迟消息（下单成功即"上闹钟"）。
        //     消费者在延迟到点后检查该订单，仍未支付则自动取消并恢复库存；
        //     发送失败不阻断下单（OrderTimeoutTask 定时任务仍会兜底扫描）。
        rocketMQProducerService.sendOrderTimeout(order.getId(), order.getOrderNo(), timeoutMinutes);

        log.info("订单创建成功: orderNo={}, userId={}, original={}, promotion={}, coupon={}, payable={}",
                order.getOrderNo(), userId, originalAmount, promotionAmount, couponAmount, totalAmount);
        return detail(userId, order.getId());
    }

    /**
     * 我的订单列表（分页 + 状态筛选；卡片带明细，批量查询避免 N+1）。
     * 强制主库读：下单/支付/取消后前端立即刷新列表，从库复制延迟会造成
     * "刚下单却看不到订单/状态不对"的体验问题。
     */
    @DS("master")
    public PageResult<OrderVO> list(Long userId, OrderQueryDTO dto) {
        // Phase 3 分表：按用户路由（列表 + 明细批量查询都落在同一张分片）
        ShardingRouter.routeOrdersByUser(userId);
        int offset = (dto.getPage() - 1) * dto.getPageSize();
        List<Order> orders = orderRepository.selectPageByUser(userId, dto.getStatus(), offset, dto.getPageSize());
        long total = orderRepository.countByUser(userId, dto.getStatus());

        List<OrderVO> vos = new ArrayList<>();
        if (!orders.isEmpty()) {
            // 批量查明细：一次 IN 查询全部订单的明细
            List<Long> orderIds = orders.stream().map(Order::getId).toList();
            Map<Long, List<OrderItem>> itemsByOrder = groupItems(orderItemRepository.selectByOrderIds(orderIds));
            for (Order o : orders) {
                OrderVO vo = toVO(o);
                vo.setItems(toItemVOs(itemsByOrder.getOrDefault(o.getId(), List.of())));
                vos.add(vo);
            }
        }
        return new PageResult<>(total, vos);
    }

    /**
     * 查询用户最近一笔订单（供 AI 客服在用户未提供订单号时兜底）。
     *
     * <p>场景：用户问"我的快递到哪了"但不带订单号——意图识别能判断"查物流"，
     * 但工具必须有一个 orderId 才能查。这里按 id 倒序取最近一笔，复用 detail
     * 的归属校验与 VO 组装；没有任何订单时返回 null，AI 侧会引导用户提供订单号。
     * 强制主库读（写后读一致性）。
     */
    @DS("master")
    public OrderVO recent(Long userId) {
        ShardingRouter.routeOrdersByUser(userId);
        List<Order> orders = orderRepository.selectPageByUser(userId, null, 0, 1);
        return orders.isEmpty() ? null : detail(userId, orders.get(0).getId());
    }

    /**
     * 按订单号查详情（AI 客服工具用）：用户聊天里发的 19 位长数字是订单号 order_no，
     * 先按 order_no + userId 精确查（归属校验），命中后复用 detail 组装完整 VO。
     * 强制主库读（写后读一致性）。
     */
    @DS("master")
    public OrderVO detailByOrderNo(Long userId, String orderNo) {
        ShardingRouter.routeOrdersByUser(userId);
        Order order = orderRepository.selectByOrderNoAndUserId(orderNo, userId);
        if (order == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "没有找到该订单号对应的订单");
        }
        return detail(userId, order.getId());
    }

    /** 订单详情（含明细；阶段 5：附状态时间轴日志）。强制主库读：下单/支付后立即跳详情。 */
    @DS("master")
    public OrderVO detail(Long userId, Long orderId) {
        ShardingRouter.routeOrdersByUser(userId);
        Order order = orderRepository.selectByIdAndUserId(orderId, userId);
        if (order == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "订单不存在");
        }
        OrderVO vo = toVO(order);
        vo.setItems(toItemVOs(orderItemRepository.selectByOrderId(orderId)));
        vo.setStatusLogs(fillLogTexts(orderStatusLogRepository.selectByOrderId(orderId)));
        return vo;
    }

    /**
     * 用户取消订单（CANCEL 事件）：仅待付款/待发货可取消。
     * 状态机校验 → 条件 UPDATE 落库 → 恢复库存 → 写状态日志。
     */
    @Transactional
    public OrderVO cancel(Long userId, Long orderId) {
        ShardingRouter.routeOrdersByUser(userId);
        Order order = orderRepository.selectByIdAndUserId(orderId, userId);
        if (order == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "订单不存在");
        }
        // 1. 状态机校验：当前状态 + CANCEL 是否合法（非法直接抛业务异常）
        int toStatus = orderStateMachine.transition(order, OrderEvent.CANCEL);
        // 2. 条件 UPDATE：状态前置（0/1）+ 归属，rows=0 说明并发下状态已变
        if (orderRepository.cancelByUser(ShardingRouter.routedTableName(ShardingRouter.T_ORDERS), orderId, userId) == 0) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "订单状态已变化，请刷新后重试");
        }
        // 3. 库存处理（阶段 6，按取消前状态区分）：
        //    待付款（0）→ 释放锁定库存；已付款未发货（1）→ 回补已扣库存（退货入库）
        if (order.getStatus() == OrderStatusEnum.UNPAID.getCode()) {
            inventoryService.release(orderId, userId, "用户取消订单（释放锁定库存）");
        } else {
            inventoryService.restoreSold(orderId, userId, "用户取消订单（回补已扣库存）");
        }
        // 阶段 8：取消订单释放锁定但未核销的优惠券（幂等：只释放 status=1 且绑定本单的券）
        couponService.release(orderId);
        // 4. 记录状态日志
        logStatus(order, toStatus, OrderEvent.CANCEL, "USER", userId, "用户取消订单");
        log.info("订单已取消并恢复库存: orderNo={}, userId={}", order.getOrderNo(), userId);
        return detail(userId, orderId);
    }

    /**
     * 买家确认收货（CONFIRM 事件）：仅待收货（2）可确认 → 已完成（3）。
     */
    @Transactional
    public OrderVO confirm(Long userId, Long orderId) {
        ShardingRouter.routeOrdersByUser(userId);
        Order order = orderRepository.selectByIdAndUserId(orderId, userId);
        if (order == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "订单不存在");
        }
        int toStatus = orderStateMachine.transition(order, OrderEvent.CONFIRM);
        if (orderRepository.confirm(ShardingRouter.routedTableName(ShardingRouter.T_ORDERS), orderId) == 0) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "订单状态已变化，请刷新后重试");
        }
        logStatus(order, toStatus, OrderEvent.CONFIRM, "USER", userId, "买家确认收货");
        log.info("订单已确认收货: orderNo={}, userId={}", order.getOrderNo(), userId);
        return detail(userId, orderId);
    }

    // ==================== 管理端 ====================

    /** 管理端订单列表（状态筛选 + 订单号搜索，带下单人用户名）。强制主库读（发货/关闭后即时刷新）。 */
    @DS("master")
    public PageResult<OrderVO> adminList(OrderQueryDTO dto) {
        // Phase 3 分表：管理端没有 userId，跨 4 张分片 UNION ALL 查询（广播）
        ShardingRouter.routeOrdersBroadcast();
        int offset = (dto.getPage() - 1) * dto.getPageSize();
        List<Order> orders = orderRepository.adminPage(dto, offset, dto.getPageSize());
        long total = orderRepository.adminCount(dto);
        List<OrderVO> vos = orders.stream().map(this::toVO).toList();
        return new PageResult<>(total, vos);
    }

    /** 管理端订单详情（含明细与状态时间轴日志）。强制主库读（发货/关闭后即时查看）。 */
    @DS("master")
    public OrderVO adminDetail(Long orderId) {
        // 第一步：广播查询定位订单（4 张分片 UNION ALL）
        ShardingRouter.routeOrdersBroadcast();
        Order order = orderRepository.selectById(orderId);
        if (order == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "订单不存在");
        }
        // 第二步：拿到 userId 后切单表路由，明细/日志查询落在同分片
        ShardingRouter.routeOrdersByUser(order.getUserId());
        OrderVO vo = toVO(order);
        vo.setItems(toItemVOs(orderItemRepository.selectByOrderId(orderId)));
        vo.setStatusLogs(fillLogTexts(orderStatusLogRepository.selectByOrderId(orderId)));
        return vo;
    }

    /**
     * 管理端发货（SHIP 事件）：仅待发货（1）可发货 → 待收货（2）。
     *
     * <p>阶段 5：由 /api/admin/orders/{id}/ship 直接调用。
     * 阶段 9 物流升级：改为由 ShippingService.ship 统一编排——先创建运单
     * （shipping + 初始轨迹）再调用本方法完成订单状态流转 1→2，
     * 保证"运单与订单状态在同一事务内一致"；旧接口保留兼容。
     *
     * @param orderId    订单 ID
     * @param shippingNo 物流单号（可空；发货弹窗填写后随请求传入）
     */
    @Transactional
    public void ship(Long orderId, String shippingNo) {
        Order order = selectByIdSharded(orderId);
        if (order == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "订单不存在");
        }
        int toStatus = orderStateMachine.transition(order, OrderEvent.SHIP);
        if (orderRepository.shipWithNo(ShardingRouter.routedTableName(ShardingRouter.T_ORDERS), orderId, shippingNo) == 0) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "订单状态已变化，请刷新后重试");
        }
        logStatus(order, toStatus, OrderEvent.SHIP, "ADMIN", null,
                shippingNo == null || shippingNo.isBlank() ? "商家发货" : "商家发货，物流单号：" + shippingNo);
        log.info("订单已发货: orderId={}, shippingNo={}", orderId, shippingNo);
    }

    /**
     * 管理端关闭订单（CLOSE 事件）：仅待付款（0）可关闭 → 已取消（4），恢复库存。
     * （阶段 5 新增，用于处理恶意订单、占用库存等问题订单）
     */
    @Transactional
    public void close(Long orderId) {
        Order order = selectByIdSharded(orderId);
        if (order == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "订单不存在");
        }
        int toStatus = orderStateMachine.transition(order, OrderEvent.CLOSE);
        if (orderRepository.cancelPending(ShardingRouter.routedTableName(ShardingRouter.T_ORDERS), orderId) == 0) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "仅待付款订单可关闭");
        }
        // 释放锁定库存（阶段 6：关闭未支付订单，预占库存回补可卖）
        inventoryService.release(orderId, null, "管理端关闭订单（释放锁定库存）");
        // 阶段 8：关闭订单释放锁定但未核销的优惠券
        couponService.release(orderId);
        logStatus(order, toStatus, OrderEvent.CLOSE, "ADMIN", null, "管理端关闭订单");
        log.info("订单已被管理端关闭并恢复库存: orderId={}", orderId);
    }

    /**
     * 售后退款关闭订单（阶段 7）：仅退款场景（订单已付款未发货，状态 1）退款成功后
     * 由 AfterSaleService 调用，把订单置为已取消（4）并写状态日志。
     *
     * <p>注意：库存回补由 AfterSaleService 统一执行（{@link InventoryService#restoreSold}），
     * 本方法只负责订单状态落库与日志，避免双重回补。
     */
    @Transactional
    public void refundClose(Long orderId, String remark) {
        Order order = selectByIdSharded(orderId);
        if (order == null) {
            return;
        }
        if (order.getStatus() != OrderStatusEnum.PAID.getCode()) {
            log.debug("售后关闭订单跳过：订单 {} 当前状态 {} 非待发货",
                    orderId, OrderStatusEnum.textOf(order.getStatus()));
            return;
        }
        if (orderRepository.refundClose(ShardingRouter.routedTableName(ShardingRouter.T_ORDERS), orderId) == 0) {
            log.warn("售后关闭订单未命中（并发状态已变）：orderId={}", orderId);
            return;
        }
        logStatus(order, OrderStatusEnum.CANCELLED.getCode(), OrderEvent.CANCEL,
                "SYSTEM", null, remark == null ? "售后退款，订单已关闭" : remark);
        log.info("售后退款订单已关闭: orderId={}", orderId);
    }

    /**
     * 支付成功标记订单已付款（PAY 事件，阶段 4 起由 PaymentService 回调处理调用）。
     *
     * <p>回调场景特殊：订单可能已被取消/已付款，此时**不抛异常**（支付平台重复通知、
     * 取消后支付等边界），幂等处理：
     * <ul>
     *   <li>订单已是待发货（1）→ 已处理过，直接返回；</li>
     *   <li>订单已取消（4）→ 记警告返回（真实场景应由支付平台发起退款）；</li>
     *   <li>待付款（0）→ 状态机校验 PAY → 条件 UPDATE 落库 → 写日志。</li>
     * </ul>
     */
    @Transactional
    public void markPaid(Long orderId) {
        Order order = selectByIdSharded(orderId);
        if (order == null) {
            log.warn("支付回调：订单 {} 不存在", orderId);
            return;
        }
        int status = order.getStatus() == null ? -1 : order.getStatus();
        if (status == OrderStatusEnum.PAID.getCode()) {
            return; // 幂等：已标记过付款
        }
        if (status != OrderStatusEnum.UNPAID.getCode()) {
            log.warn("支付回调：订单 {} 非待付款状态（当前={}），未更新（真实场景应由支付平台退款）",
                    orderId, OrderStatusEnum.textOf(status));
            return;
        }
        int toStatus = orderStateMachine.transition(order, OrderEvent.PAY);
        if (orderRepository.markPaid(ShardingRouter.routedTableName(ShardingRouter.T_ORDERS), orderId) == 0) {
            log.warn("支付回调：订单 {} 状态并发变化，本次回调忽略", orderId);
            return;
        }
        // 阶段 6：支付成功 → 把下单时锁定的库存正式扣减（DEDUCT 流水），
        // 幂等由"状态机 + markPaid 条件 UPDATE"保证只执行一次
        inventoryService.deduct(orderId);
        // 阶段 8：支付成功 → 核销下单时锁定的优惠券（幂等：只核销 status=1 的券）
        couponService.markUsed(orderId);
        // Phase 2：支付成功 → 发异步通知消息（消费者负责记录 PURCHASE 购买行为 + 发送通知）。
        // 回调主线程只做强一致性动作（状态/库存/券），收尾动作异步化后回调响应更快；
        // 消息发送失败时降级为同步记录购买行为（行为一条不丢，通知丢失可接受）。
        boolean payQueued = rocketMQProducerService.sendPaySuccess(
                order.getId(), order.getUserId(), null, null);
        if (!payQueued) {
            try {
                for (OrderItem item : orderItemRepository.selectByOrderId(orderId)) {
                    recommendService.recordSync(order.getUserId(), item.getProductId(), item.getSkuId(),
                            com.mall.repository.UserBehaviorRepository.TYPE_PURCHASE);
                }
            } catch (Exception e) {
                log.warn("记录购买行为失败（不影响支付）: orderId={}, err={}", orderId, e.getMessage());
            }
        }
        logStatus(order, toStatus, OrderEvent.PAY, "SYSTEM", null, "支付成功（支付平台回调）");
    }

    // ==================== 定时任务支撑 ====================

    /**
     * 查询超时未支付订单 ID 列表（OrderTimeoutTask 定时调用）。
     *
     * @param timeoutMinutes 超时阈值（分钟）
     * @return 待付款且创建时间超过阈值的订单 ID
     */
    public List<Long> findTimeoutOrderIds(int timeoutMinutes) {
        // Phase 3 分表：跨 4 片广播查询超时订单
        ShardingRouter.routeOrdersBroadcast();
        return orderRepository.selectTimeoutOrders(timeoutMinutes, 100)
                .stream().map(Order::getId).toList();
    }

    /**
     * 取消单笔超时订单（TIMEOUT 事件，OrderTimeoutTask 调用）。
     * 系统内部动作：状态不合法（如刚被用户支付/取消）时静默跳过，不抛异常。
     */
    @Transactional
    public void timeoutCancel(Long orderId) {
        Order order = selectByIdSharded(orderId);
        if (order == null) {
            return;
        }
        if (!orderStateMachine.canTransition(order.getStatus(), OrderEvent.TIMEOUT)) {
            log.debug("超时任务跳过：订单 {} 状态 {} 不可超时取消",
                    orderId, OrderStatusEnum.textOf(order.getStatus()));
            return;
        }
        int toStatus = orderStateMachine.transition(order, OrderEvent.TIMEOUT);
        if (orderRepository.cancelPending(ShardingRouter.routedTableName(ShardingRouter.T_ORDERS), orderId) == 0) {
            return; // 并发下状态已变（如已支付），跳过
        }
        // 阶段 6：超时取消 → 释放锁定库存（预占库存回补可卖）
        inventoryService.release(orderId, null, "超时未支付，系统自动取消（释放锁定库存）");
        // 阶段 8：超时取消释放锁定但未核销的优惠券
        couponService.release(orderId);
        logStatus(order, toStatus, OrderEvent.TIMEOUT, "SYSTEM", null, "超时未支付，系统自动取消");
        log.info("超时订单已自动取消并恢复库存: orderId={}", orderId);
    }

    /**
     * Phase 2：RocketMQ 延迟消息到点后的"检查并取消"入口。
     *
     * <p>为什么到点还要再校验时间：RocketMQ 4.9.x 延迟级别是固定档位
     * （1s/5s/…/30m/1h/2h），如果配置的 {@code order.timeout-minutes} 与
     * 发送时映射的档位不完全相等（如配置 40 分钟 → 消息 30 分钟就到点），
     * 消费者必须按"真实配置阈值"再判断一次，未到阈值就跳过（下个兜底周期处理）。
     *
     * @param orderId        订单 ID
     * @param timeoutMinutes 真实超时阈值（分钟，与 OrderTimeoutTask 一致）
     */
    @Transactional
    public void timeoutCancelIfDue(Long orderId, int timeoutMinutes) {
        Order order = selectByIdSharded(orderId);
        if (order == null || order.getCreatedAt() == null) {
            return;
        }
        if (order.getStatus() == null || order.getStatus() != OrderStatusEnum.UNPAID.getCode()) {
            log.debug("超时延迟消息跳过（订单已处理）: orderId={}, status={}",
                    orderId, OrderStatusEnum.textOf(order.getStatus()));
            return;
        }
        if (order.getCreatedAt().isAfter(java.time.LocalDateTime.now().minusMinutes(timeoutMinutes))) {
            log.debug("超时延迟消息跳过（未到超时阈值）: orderId={}, createdAt={}, threshold={}min",
                    orderId, order.getCreatedAt(), timeoutMinutes);
            return;
        }
        timeoutCancel(orderId);
    }

    // ==================== 私有工具 ====================

    /**
     * Phase 3 分表：管理端/系统按订单 ID 查订单的统一入口。
     *
     * <p>调用方（ship/close/markPaid/timeoutCancel 等）只持有 orderId 没有 userId，
     * 无法直接定位分片。分两步：
     * <ol>
     *   <li>广播查询（4 张分片 UNION ALL）定位订单，拿到 userId；</li>
     *   <li>切单表路由（routeOrdersByUser）——保证同请求内后续的明细查询 /
     *       条件 UPDATE 落在同一分片，不会写错表。</li>
     * </ol>
     * 订单不存在时返回 null（此时不切路由，调用方自行处理）。
     */
    private Order selectByIdSharded(Long orderId) {
        ShardingRouter.routeOrdersBroadcast();
        Order order = orderRepository.selectById(orderId);
        if (order != null) {
            ShardingRouter.routeOrdersByUser(order.getUserId());
        }
        return order;
    }

    /**
     * 写一条状态变更日志（所有流转的统一出口）。
     *
     * @param order        流转前订单（含 id/orderNo/status）
     * @param toStatus     目标状态码（由状态机算出）
     * @param event        触发事件
     * @param operatorType 操作方：USER/ADMIN/SYSTEM
     * @param operatorId   操作人 ID（SYSTEM 为 null）
     * @param remark       备注
     */
    private void logStatus(Order order, int toStatus, OrderEvent event,
                           String operatorType, Long operatorId, String remark) {
        OrderStatusLog logEntry = new OrderStatusLog();
        logEntry.setOrderId(order.getId());
        logEntry.setOrderNo(order.getOrderNo());
        logEntry.setFromStatus(order.getStatus());
        logEntry.setToStatus(toStatus);
        logEntry.setEvent(event.name());
        logEntry.setOperatorType(operatorType);
        logEntry.setOperatorId(operatorId);
        logEntry.setRemark(remark);
        orderStatusLogRepository.insert(logEntry);
    }

    /** 状态日志列表补充状态中文名（前端时间轴直接展示） */
    private List<OrderStatusLog> fillLogTexts(List<OrderStatusLog> logs) {
        for (OrderStatusLog logEntry : logs) {
            logEntry.setFromStatusText(OrderStatusEnum.textOf(logEntry.getFromStatus()));
            logEntry.setToStatusText(OrderStatusEnum.textOf(logEntry.getToStatus()));
        }
        return logs;
    }

    /** 实体 → VO（含状态中文名与物流单号） */
    private OrderVO toVO(Order o) {
        OrderVO vo = new OrderVO();
        // 雪花 ID 转字符串输出：19 位超 JS 精度，数字会被前端解析丢位（见 OrderVO.id 注释）
        vo.setId(String.valueOf(o.getId()));
        vo.setOrderNo(o.getOrderNo());
        vo.setUserId(o.getUserId());
        vo.setUsername(o.getUsername());
        vo.setTotalAmount(o.getTotalAmount());
        vo.setOriginalAmount(o.getOriginalAmount());
        vo.setPromotionAmount(o.getPromotionAmount());
        vo.setCouponAmount(o.getCouponAmount());
        vo.setStatus(o.getStatus());
        vo.setStatusText(OrderStatusEnum.textOf(o.getStatus()));
        vo.setReceiverName(o.getReceiverName());
        // Phase 1：订单快照手机号加密存储，展示时解密
        vo.setReceiverPhone(sensitiveUtil.decrypt(o.getReceiverPhone()));
        vo.setReceiverAddress(o.getReceiverAddress());
        vo.setRemark(o.getRemark());
        vo.setShippingNo(o.getShippingNo());
        vo.setCreatedAt(o.getCreatedAt());
        vo.setPaidAt(o.getPaidAt());
        vo.setShippedAt(o.getShippedAt());
        vo.setCompletedAt(o.getCompletedAt());
        vo.setCancelledAt(o.getCancelledAt());
        return vo;
    }

    /** 明细列表 → VO 列表（补小计） */
    private List<OrderItemVO> toItemVOs(List<OrderItem> items) {
        List<OrderItemVO> vos = new ArrayList<>();
        for (OrderItem item : items) {
            OrderItemVO vo = new OrderItemVO();
            vo.setOrderItemId(item.getId());
            vo.setProductId(item.getProductId());
            vo.setProductName(item.getProductName());
            vo.setProductImage(item.getProductImage());
            vo.setSkuId(item.getSkuId());
            vo.setSkuName(item.getSkuName());
            vo.setPrice(item.getPrice());
            vo.setQuantity(item.getQuantity());
            vo.setSubtotal(item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            vos.add(vo);
        }
        return vos;
    }

    /** 明细按订单分组（列表批量填充用） */
    private Map<Long, List<OrderItem>> groupItems(List<OrderItem> items) {
        Map<Long, List<OrderItem>> map = new HashMap<>();
        for (OrderItem item : items) {
            map.computeIfAbsent(item.getOrderId(), k -> new ArrayList<>()).add(item);
        }
        return map;
    }

    /** 单价解析：有规格用规格价，否则用商品价（与购物车一致） */
    private BigDecimal resolvePrice(Cart cart) {
        if (cart.getSkuId() != null && cart.getSkuId() > 0 && cart.getSkuPrice() != null) {
            return cart.getSkuPrice();
        }
        return cart.getProductPrice();
    }

    /** 库存解析：有规格用规格可卖库存，否则用商品可卖库存（JOIN 已按 stock - locked_stock 计算） */
    private int resolveStock(Cart cart) {
        if (cart.getSkuId() != null && cart.getSkuId() > 0 && cart.getSkuStock() != null) {
            return cart.getSkuStock();
        }
        return cart.getProductStock() == null ? 0 : cart.getProductStock();
    }
}
