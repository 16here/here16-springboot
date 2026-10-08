package com.mall.service;

import com.mall.common.BusinessException;
import com.mall.common.OrderNoGenerator;
import com.mall.config.PaymentConfig;
import com.mall.dto.PaymentCallbackDTO;
import com.mall.dto.PaymentResponseVO;
import com.mall.entity.Order;
import com.mall.entity.Payment;
import com.mall.repository.OrderRepository;
import com.mall.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 支付服务：发起支付、查询状态、处理回调。
 *
 * <p>核心流程（发起支付）：
 * <ol>
 *   <li>校验订单归属（userId 从登录上下文取）与状态（仅待付款 0 可发起支付）；</li>
 *   <li>复用该订单已有的"待支付"流水（幂等，防止重复创建）；</li>
 *   <li>生成支付流水号，按订单金额创建流水（金额由后端锁定，前端不可改）；</li>
 *   <li>通过 {@link PaymentGatewayFactory} 取支付方式实现，生成支付凭证返回。</li>
 * </ol>
 *
 * <p>回调处理：验签 → 校验金额 → 条件更新流水（幂等）→ 订单标记已付款。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    /** 流水状态 → 中文名 */
    private static final Map<Integer, String> STATUS_TEXT = Map.of(
            0, "待支付", 1, "已支付", 2, "已退款", 3, "支付失败"
    );

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaymentGatewayFactory gatewayFactory;
    private final PaymentConfig paymentConfig;
    private final OrderNoGenerator orderNoGenerator;
    private final OrderService orderService;

    /**
     * 发起支付：校验订单 → 复用/创建待支付流水 → 生成支付凭证。
     */
    public PaymentResponseVO createPayment(Long userId, Long orderId, String payMethod) {
        // 1. 订单归属校验（只能支付自己的订单）
        Order order = orderRepository.selectByIdAndUserId(orderId, userId);
        if (order == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "订单不存在");
        }
        // 2. 状态校验：仅待付款可发起支付
        if (order.getStatus() != 0) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "订单当前状态不可支付");
        }
        // 3. 支付方式白名单校验 + 取网关实现（不支持的支付方式在此抛 400）
        PaymentGateway gateway = gatewayFactory.get(payMethod);

        // 4. 复用该订单已有的待支付流水（防止用户反复点支付创建一堆流水）
        Payment payment = paymentRepository.selectByOrderIdAndStatus(orderId, 0);
        if (payment == null) {
            payment = new Payment();
            payment.setPaymentNo("PAY" + orderNoGenerator.generate(userId));
            payment.setOrderId(orderId);
            payment.setUserId(userId);
            payment.setPayMethod(payMethod);
            payment.setAmount(order.getTotalAmount());
            payment.setStatus(0);
            paymentRepository.insert(payment);
            log.info("创建支付流水: paymentNo={}, orderId={}, amount={}", payment.getPaymentNo(), orderId, order.getTotalAmount());
        }

        // 5. 生成支付凭证（真实/mock 由网关实现决定）
        String payUrl = gateway.createPayUrl(payment.getPaymentNo(), payment.getAmount());

        PaymentResponseVO vo = new PaymentResponseVO();
        vo.setPaymentNo(payment.getPaymentNo());
        vo.setOrderId(orderId);
        vo.setOrderNo(order.getOrderNo());
        vo.setPayMethod(payMethod);
        vo.setAmount(payment.getAmount());
        vo.setPayUrl(payUrl);
        vo.setStatus(payment.getStatus());
        vo.setStatusText(STATUS_TEXT.getOrDefault(payment.getStatus(), "未知"));
        vo.setExpireSeconds(paymentConfig.getExpireSeconds());
        vo.setMock(paymentConfig.isMockEnabled());
        // mock 模式返回一次性签名：前端模拟支付时携带，后端验签通过才更新
        if (paymentConfig.isMockEnabled()) {
            vo.setMockSign(paymentConfig.mockSign(payment.getPaymentNo(), payment.getAmount().toPlainString()));
        }
        return vo;
    }

    /** 查询支付状态（归属校验：只能查自己的流水） */
    public PaymentResponseVO queryStatus(Long userId, String paymentNo) {
        Payment payment = paymentRepository.findByPaymentNo(paymentNo);
        if (payment == null || !payment.getUserId().equals(userId)) {
            throw new BusinessException(BusinessException.NOT_FOUND, "支付流水不存在");
        }
        Order order = orderRepository.selectByIdAndUserId(payment.getOrderId(), userId);

        PaymentResponseVO vo = new PaymentResponseVO();
        vo.setPaymentNo(payment.getPaymentNo());
        vo.setOrderId(payment.getOrderId());
        vo.setOrderNo(order == null ? null : order.getOrderNo());
        vo.setPayMethod(payment.getPayMethod());
        vo.setAmount(payment.getAmount());
        vo.setStatus(payment.getStatus());
        vo.setStatusText(STATUS_TEXT.getOrDefault(payment.getStatus(), "未知"));
        vo.setPaidAt(payment.getPaidAt());
        vo.setMock(paymentConfig.isMockEnabled());
        return vo;
    }

    /**
     * 处理支付回调（由支付平台异步调用，接口对外放行）。
     *
     * <p>流程：验签 → 解析流水号/金额/交易号 → 幂等更新流水 → 订单标记已付款。
     * 兼容字段名：真实支付宝回调参数为 out_trade_no / total_amount / trade_no，
     * mock 回调参数为 paymentNo / amount / tradeNo。
     */
    @Transactional
    public PaymentCallbackDTO handleCallback(String payMethod, Map<String, String> params) {
        PaymentCallbackDTO result = new PaymentCallbackDTO();

        // 1. 验签（mock：校验后端签名；真实：SDK 验签）
        PaymentGateway gateway = gatewayFactory.get(payMethod);
        if (!gateway.verifyCallback(params)) {
            result.setSuccess(false);
            result.setMessage("回调验签失败");
            log.warn("支付回调验签失败: payMethod={}", payMethod);
            return result;
        }

        // 2. 解析回调参数（兼容真实/mock 字段名）
        String paymentNo = firstNotNull(params, "paymentNo", "out_trade_no");
        String amountStr = firstNotNull(params, "amount", "total_amount");
        String transactionId = firstNotNull(params, "tradeNo", "trade_no");
        if (paymentNo == null || amountStr == null) {
            result.setSuccess(false);
            result.setMessage("回调参数不完整");
            return result;
        }

        // 3. 流水必须存在
        Payment payment = paymentRepository.findByPaymentNo(paymentNo);
        if (payment == null) {
            result.setSuccess(false);
            result.setMessage("支付流水不存在");
            return result;
        }

        // 4. 金额校验：回调金额必须与流水锁定金额一致（防止篡改）
        BigDecimal amount;
        try {
            amount = new BigDecimal(amountStr);
        } catch (NumberFormatException e) {
            result.setSuccess(false);
            result.setMessage("回调金额格式错误");
            return result;
        }
        if (amount.compareTo(payment.getAmount()) != 0) {
            result.setSuccess(false);
            result.setMessage("回调金额与订单金额不一致");
            log.warn("支付回调金额不一致: paymentNo={}, 回调={}, 流水={}",
                    paymentNo, amount, payment.getAmount());
            return result;
        }

        // 5. 幂等更新流水：条件 UPDATE（仅待支付可转已支付），rows=0 说明已处理过
        int updated = paymentRepository.updateToPaid(payment.getId(), transactionId);
        if (updated == 0) {
            // 已支付过（重复回调），直接返回成功，避免支付平台重复通知
            result.setSuccess(true);
            result.setMessage("重复回调，已处理");
            result.setPaymentNo(paymentNo);
            return result;
        }

        // 6. 订单标记已付款（0 待付款 → 1 待发货；订单已取消等边界场景不报错）
        orderService.markPaid(payment.getOrderId());

        log.info("支付回调处理成功: paymentNo={}, orderId={}, transactionId={}",
                paymentNo, payment.getOrderId(), transactionId);
        result.setSuccess(true);
        result.setMessage("success");
        result.setPaymentNo(paymentNo);
        return result;
    }

    /** 取第一个非空参数（真实与 mock 字段名兼容） */
    private static String firstNotNull(Map<String, String> params, String... keys) {
        for (String key : keys) {
            String v = params.get(key);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }
}
