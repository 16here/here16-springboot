package com.mall.service;

import com.mall.common.BusinessException;
import com.mall.common.OrderNoGenerator;
import com.mall.common.PageResult;
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
        Order order = new Order();
        order.setOrderNo(orderNoGenerator.generate(userId));
        order.setUserId(userId);
        order.setOriginalAmount(originalAmount);
        order.setPromotionAmount(promotionAmount);
        order.setCouponAmount(couponAmount);
        order.setCouponId(userCouponId);
        order.setTotalAmount(totalAmount);
        order.setStatus(OrderStatusEnum.UNPAID.getCode());
        order.setReceiverName(address.getReceiverName());
        order.setReceiverPhone(address.getReceiverPhone());
        order.setReceiverAddress(address.getProvince() + address.getCity()
                + address.getDistrict() + address.getDetail());
        order.setRemark(dto.getRemark());
        orderRepository.insert(order);

        // 9. 锁优惠券（订单落库后绑定 orderId；条件 UPDATE，失败整体回滚）
        if (userCouponId != null) {
            couponService.lock(userId, userCouponId, order.getId());
        }

        // 10. 写订单明细（商品快照）
        for (OrderItem item : items) {
            item.setOrderId(order.getId());
        }
        orderItemRepository.insertBatch(items);

        // 11. 锁定库存（阶段 6：预占式库存，不再直接扣减）
        //     Redis 预减（快速拦截超卖）→ 数据库条件锁定（最终防线）→ 写 LOCK 流水；
        //     任一步失败整体回滚（Redis 侧已由 InventoryService 回补）。
        inventoryService.lock(order.getId(), order.getOrderNo(), userId, items);

        // 12. 清掉已下单的购物车项
        for (Long cartId : dto.getCartIds()) {
            cartRepository.deleteByIdAndUserId(cartId, userId);
        }

        log.info("订单创建成功: orderNo={}, userId={}, original={}, promotion={}, coupon={}, payable={}",
                order.getOrderNo(), userId, originalAmount, promotionAmount, couponAmount, totalAmount);
        return detail(userId, order.getId());
    }

    /** 我的订单列表（分页 + 状态筛选；卡片带明细，批量查询避免 N+1） */
    public PageResult<OrderVO> list(Long userId, OrderQueryDTO dto) {
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

    /** 订单详情（含明细；阶段 5：附状态时间轴日志） */
    public OrderVO detail(Long userId, Long orderId) {
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
        Order order = orderRepository.selectByIdAndUserId(orderId, userId);
        if (order == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "订单不存在");
        }
        // 1. 状态机校验：当前状态 + CANCEL 是否合法（非法直接抛业务异常）
        int toStatus = orderStateMachine.transition(order, OrderEvent.CANCEL);
        // 2. 条件 UPDATE：状态前置（0/1）+ 归属，rows=0 说明并发下状态已变
        if (orderRepository.cancelByUser(orderId, userId) == 0) {
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
        Order order = orderRepository.selectByIdAndUserId(orderId, userId);
        if (order == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "订单不存在");
        }
        int toStatus = orderStateMachine.transition(order, OrderEvent.CONFIRM);
        if (orderRepository.confirm(orderId) == 0) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "订单状态已变化，请刷新后重试");
        }
        logStatus(order, toStatus, OrderEvent.CONFIRM, "USER", userId, "买家确认收货");
        log.info("订单已确认收货: orderNo={}, userId={}", order.getOrderNo(), userId);
        return detail(userId, orderId);
    }

    // ==================== 管理端 ====================

    /** 管理端订单列表（状态筛选 + 订单号搜索，带下单人用户名） */
    public PageResult<OrderVO> adminList(OrderQueryDTO dto) {
        int offset = (dto.getPage() - 1) * dto.getPageSize();
        List<Order> orders = orderRepository.adminPage(dto, offset, dto.getPageSize());
        long total = orderRepository.adminCount(dto);
        List<OrderVO> vos = orders.stream().map(this::toVO).toList();
        return new PageResult<>(total, vos);
    }

    /** 管理端订单详情（含明细与状态时间轴日志） */
    public OrderVO adminDetail(Long orderId) {
        Order order = orderRepository.selectById(orderId);
        if (order == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "订单不存在");
        }
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
        Order order = orderRepository.selectById(orderId);
        if (order == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "订单不存在");
        }
        int toStatus = orderStateMachine.transition(order, OrderEvent.SHIP);
        if (orderRepository.shipWithNo(orderId, shippingNo) == 0) {
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
        Order order = orderRepository.selectById(orderId);
        if (order == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "订单不存在");
        }
        int toStatus = orderStateMachine.transition(order, OrderEvent.CLOSE);
        if (orderRepository.cancelPending(orderId) == 0) {
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
        Order order = orderRepository.selectById(orderId);
        if (order == null) {
            return;
        }
        if (order.getStatus() != OrderStatusEnum.PAID.getCode()) {
            log.debug("售后关闭订单跳过：订单 {} 当前状态 {} 非待发货",
                    orderId, OrderStatusEnum.textOf(order.getStatus()));
            return;
        }
        if (orderRepository.refundClose(orderId) == 0) {
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
        Order order = orderRepository.selectById(orderId);
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
        if (orderRepository.markPaid(orderId) == 0) {
            log.warn("支付回调：订单 {} 状态并发变化，本次回调忽略", orderId);
            return;
        }
        // 阶段 6：支付成功 → 把下单时锁定的库存正式扣减（DEDUCT 流水），
        // 幂等由"状态机 + markPaid 条件 UPDATE"保证只执行一次
        inventoryService.deduct(orderId);
        // 阶段 8：支付成功 → 核销下单时锁定的优惠券（幂等：只核销 status=1 的券）
        couponService.markUsed(orderId);
        // 阶段 10 埋点：支付成功 → 给订单明细逐条记录购买行为（PURCHASE，权重 5），
        // 推荐系统的"购买"信号（比浏览/加购强得多）；记录失败不影响支付主流程
        try {
            for (OrderItem item : orderItemRepository.selectByOrderId(orderId)) {
                recommendService.record(order.getUserId(), item.getProductId(), item.getSkuId(),
                        com.mall.repository.UserBehaviorRepository.TYPE_PURCHASE);
            }
        } catch (Exception e) {
            log.warn("记录购买行为失败（不影响支付）: orderId={}, err={}", orderId, e.getMessage());
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
        return orderRepository.selectTimeoutOrders(timeoutMinutes, 100)
                .stream().map(Order::getId).toList();
    }

    /**
     * 取消单笔超时订单（TIMEOUT 事件，OrderTimeoutTask 调用）。
     * 系统内部动作：状态不合法（如刚被用户支付/取消）时静默跳过，不抛异常。
     */
    @Transactional
    public void timeoutCancel(Long orderId) {
        Order order = orderRepository.selectById(orderId);
        if (order == null) {
            return;
        }
        if (!orderStateMachine.canTransition(order.getStatus(), OrderEvent.TIMEOUT)) {
            log.debug("超时任务跳过：订单 {} 状态 {} 不可超时取消",
                    orderId, OrderStatusEnum.textOf(order.getStatus()));
            return;
        }
        int toStatus = orderStateMachine.transition(order, OrderEvent.TIMEOUT);
        if (orderRepository.cancelPending(orderId) == 0) {
            return; // 并发下状态已变（如已支付），跳过
        }
        // 阶段 6：超时取消 → 释放锁定库存（预占库存回补可卖）
        inventoryService.release(orderId, null, "超时未支付，系统自动取消（释放锁定库存）");
        // 阶段 8：超时取消释放锁定但未核销的优惠券
        couponService.release(orderId);
        logStatus(order, toStatus, OrderEvent.TIMEOUT, "SYSTEM", null, "超时未支付，系统自动取消");
        log.info("超时订单已自动取消并恢复库存: orderId={}", orderId);
    }

    // ==================== 私有工具 ====================

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
        vo.setId(o.getId());
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
        vo.setReceiverPhone(o.getReceiverPhone());
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
