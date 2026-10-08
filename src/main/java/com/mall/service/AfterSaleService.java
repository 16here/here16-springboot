package com.mall.service;

import com.mall.common.BusinessException;
import com.mall.common.PageResult;
import com.mall.dto.AfterSaleAuditDTO;
import com.mall.dto.AfterSaleCreateDTO;
import com.mall.dto.AfterSaleVO;
import com.mall.dto.OrderItemVO;
import com.mall.entity.AfterSale;
import com.mall.entity.Order;
import com.mall.entity.OrderItem;
import com.mall.enums.OrderStatusEnum;
import com.mall.repository.AfterSaleRepository;
import com.mall.repository.OrderItemRepository;
import com.mall.repository.OrderRepository;
import com.mall.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 售后单服务（阶段 7）：申请售后、撤销、填退货物流、管理端审核与退款。
 *
 * <p>售后流程（两条链路）：
 * <pre>
 *   仅退款（未发货）  : 申请(0) → 管理端审核通过 → 直接退款 → 已完成(2)
 *                      （回补库存 + 支付流水置退款 + 订单关闭 1→4）
 *   退货退款（已发货） : 申请(0) → 审核通过 → 待寄回(1) → 用户填物流单号
 *                      → 管理端确认收货退款 → 已完成(2)
 * </pre>
 *
 * <p>关键设计：
 * <ul>
 *   <li><b>退款金额不信任前端</b>：只允许 ≤ 订单实付金额，其余由后端从订单读取；</li>
 *   <li><b>退款只执行一次</b>：状态流转全部"条件 UPDATE + 状态前置"，
 *       重复审核/重复确认退款影响行数为 0 即拒绝，不会重复回补库存、重复退流水；</li>
 *   <li><b>退款三联动</b>（同一事务）：回补已扣库存（restoreSold）＋
 *       该订单已支付流水置已退款＋仅退款未发货时关闭订单（写状态日志）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AfterSaleService {

    /** 售后类型 */
    public static final int TYPE_REFUND_ONLY = 1;   // 仅退款（钱退，货不退）
    public static final int TYPE_RETURN_REFUND = 2; // 退货退款（货与钱都退）

    /** 售后状态 */
    public static final int STATUS_PENDING = 0;    // 待审核
    public static final int STATUS_WAIT_RETURN = 1; // 待寄回（退货退款已审核通过）
    public static final int STATUS_DONE = 2;        // 已完成（已退款）
    public static final int STATUS_REJECTED = 3;    // 已拒绝
    public static final int STATUS_CANCELLED = 4;   // 已撤销

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final AfterSaleRepository afterSaleRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final InventoryService inventoryService;
    private final OrderService orderService;

    // ==================== 用户端 ====================

    /**
     * 申请售后。
     * 校验链：订单归属 → 订单状态可售后（待发货/待收货/已完成，待付款与已取消不可）
     * → 该订单无进行中售后单 → 退款金额 ≤ 订单实付 → 落库（待审核）。
     */
    @Transactional
    public AfterSaleVO apply(Long userId, AfterSaleCreateDTO dto) {
        Order order = orderRepository.selectByIdAndUserId(dto.getOrderId(), userId);
        if (order == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "订单不存在");
        }
        // 订单状态校验：0 待付款（应先去付款或取消）、4 已取消（不可售后）
        int status = order.getStatus() == null ? -1 : order.getStatus();
        if (status == OrderStatusEnum.UNPAID.getCode()) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "待付款订单不能申请售后，请先完成付款");
        }
        if (status == OrderStatusEnum.CANCELLED.getCode()) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "已取消订单不能申请售后");
        }
        // 防重复：一个订单同时只能有一条进行中（待审核/待寄回）售后单
        if (afterSaleRepository.selectActiveByOrderId(order.getId()) != null) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "该订单已有进行中的售后申请");
        }
        // 退款金额上限：不得大于订单实付金额
        if (dto.getAmount().compareTo(order.getTotalAmount()) > 0) {
            throw new BusinessException(BusinessException.PARAM_ERROR,
                    "退款金额不能超过订单实付金额 ¥" + order.getTotalAmount());
        }
        // 落库
        AfterSale as = new AfterSale();
        as.setAfterSaleNo(generateNo(userId));
        as.setOrderId(order.getId());
        as.setOrderNo(order.getOrderNo());
        as.setUserId(userId);
        as.setType(dto.getType());
        as.setReason(dto.getReason().trim());
        as.setAmount(dto.getAmount());
        as.setStatus(STATUS_PENDING);
        afterSaleRepository.insert(as);
        log.info("售后申请已提交: afterSaleNo={}, orderNo={}, type={}, amount={}",
                as.getAfterSaleNo(), order.getOrderNo(), dto.getType(), dto.getAmount());
        return toVO(as);
    }

    /** 我的售后列表（状态筛选 + 分页） */
    public PageResult<AfterSaleVO> list(Long userId, Integer status, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<AfterSale> list = afterSaleRepository.selectPageByUser(userId, status, offset, pageSize);
        long total = afterSaleRepository.countByUser(userId, status);
        List<AfterSaleVO> vos = list.stream().map(this::toVO).toList();
        return new PageResult<>(total, vos);
    }

    /** 售后详情（归属校验；附带订单商品明细快照） */
    public AfterSaleVO detail(Long userId, Long afterSaleId) {
        AfterSale as = afterSaleRepository.selectByIdAndUserId(afterSaleId, userId);
        if (as == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "售后单不存在");
        }
        AfterSaleVO vo = toVO(as);
        vo.setItems(toItemVOs(orderItemRepository.selectByOrderId(as.getOrderId())));
        return vo;
    }

    /** 撤销售后申请：仅待审核（0）可撤销 → 已撤销（4） */
    @Transactional
    public AfterSaleVO cancelApply(Long userId, Long afterSaleId) {
        AfterSale as = afterSaleRepository.selectByIdAndUserId(afterSaleId, userId);
        if (as == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "售后单不存在");
        }
        if (afterSaleRepository.cancelApply(afterSaleId, userId) == 0) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "该售后单当前状态不可撤销（仅待审核可撤销）");
        }
        as.setStatus(STATUS_CANCELLED);
        return toVO(as);
    }

    /** 填写退货物流单号：仅退货退款且待寄回（1）可填 */
    @Transactional
    public AfterSaleVO fillLogistics(Long userId, Long afterSaleId, String logisticsNo) {
        if (logisticsNo == null || logisticsNo.isBlank()) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "请填写退货物流单号");
        }
        AfterSale as = afterSaleRepository.selectByIdAndUserId(afterSaleId, userId);
        if (as == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "售后单不存在");
        }
        if (as.getType() != TYPE_RETURN_REFUND) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "仅退货退款需要填写物流单号");
        }
        if (afterSaleRepository.fillLogistics(afterSaleId, userId, logisticsNo.trim()) == 0) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "当前状态不可填写物流单号（仅待寄回可填）");
        }
        as.setLogisticsNo(logisticsNo.trim());
        return toVO(as);
    }

    // ==================== 管理端 ====================

    /** 管理端售后分页（类型/状态筛选） */
    public PageResult<AfterSaleVO> adminList(Integer type, Integer status, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<AfterSale> list = afterSaleRepository.adminPage(type, status, offset, pageSize);
        long total = afterSaleRepository.adminCount(type, status);
        List<AfterSaleVO> vos = list.stream().map(this::toVO).toList();
        return new PageResult<>(total, vos);
    }

    /** 管理端售后详情（附带订单商品明细） */
    public AfterSaleVO adminDetail(Long afterSaleId) {
        AfterSale as = afterSaleRepository.selectById(afterSaleId);
        if (as == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "售后单不存在");
        }
        AfterSaleVO vo = toVO(as);
        vo.setItems(toItemVOs(orderItemRepository.selectByOrderId(as.getOrderId())));
        return vo;
    }

    /**
     * 管理端审核售后申请。
     * <ul>
     *   <li>approve=false：拒绝（0→3，拒绝意见必填）；</li>
     *   <li>approve=true + 仅退款：直接执行退款（0→2，三联动）；</li>
     *   <li>approve=true + 退货退款：审核通过进入待寄回（0→1），等用户寄回。 </li>
     * </ul>
     */
    @Transactional
    public AfterSaleVO audit(Long afterSaleId, AfterSaleAuditDTO dto) {
        AfterSale as = afterSaleRepository.selectById(afterSaleId);
        if (as == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "售后单不存在");
        }
        if (as.getStatus() != STATUS_PENDING) {
            throw new BusinessException(BusinessException.PARAM_ERROR,
                    "该售后单已审核过，当前状态：" + statusText(as.getStatus()));
        }
        if (Boolean.FALSE.equals(dto.getApprove())) {
            String remark = dto.getRemark() == null || dto.getRemark().isBlank()
                    ? "审核不通过" : dto.getRemark().trim();
            if (afterSaleRepository.reject(afterSaleId, remark) == 0) {
                throw new BusinessException(BusinessException.PARAM_ERROR, "售后单状态已变化，请刷新后重试");
            }
            log.info("售后审核拒绝: afterSaleNo={}, remark={}", as.getAfterSaleNo(), remark);
            as.setStatus(STATUS_REJECTED);
            as.setAuditRemark(remark);
            return toVO(as);
        }
        if (as.getType() == TYPE_RETURN_REFUND) {
            String remark = dto.getRemark() == null || dto.getRemark().isBlank()
                    ? "审核通过，请尽快寄回商品" : dto.getRemark().trim();
            if (afterSaleRepository.approveReturn(afterSaleId, remark) == 0) {
                throw new BusinessException(BusinessException.PARAM_ERROR, "售后单状态已变化，请刷新后重试");
            }
            log.info("售后审核通过（退货退款）: afterSaleNo={}", as.getAfterSaleNo());
            as.setStatus(STATUS_WAIT_RETURN);
            as.setAuditRemark(remark);
            return toVO(as);
        }
        // 仅退款：审核通过直接退款
        refund(as);
        return toVO(as);
    }

    /**
     * 管理端确认收货并退款（退货退款场景：用户寄回 → 商家收到退货 → 退款）。
     * 仅待寄回（1）可确认 → 已完成（2）。
     */
    @Transactional
    public AfterSaleVO confirmRefund(Long afterSaleId) {
        AfterSale as = afterSaleRepository.selectById(afterSaleId);
        if (as == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "售后单不存在");
        }
        if (as.getStatus() != STATUS_WAIT_RETURN) {
            throw new BusinessException(BusinessException.PARAM_ERROR,
                    "仅待寄回的退货退款可确认退款，当前状态：" + statusText(as.getStatus()));
        }
        refund(as);
        return toVO(as);
    }

    /**
     * 执行退款（售后状态 0/1 → 2，同一事务内完成三联动）：
     * ① 回补已扣库存（restoreSold：库存加回 + Redis 回补 + RELEASE 流水）；
     * ② 该订单已支付流水置已退款（幂等：仅 status=1 → 2）；
     * ③ 仅退款且订单仍待发货 → 关闭订单（1→4 + 状态日志，操作方 SYSTEM）。
     */
    @Transactional
    private void refund(AfterSale as) {
        // 状态前置：仅待审核/待寄回可置已完成，rows=0 说明已退款完成（重复操作直接拒绝）
        if (afterSaleRepository.markRefunded(as.getId()) == 0) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "该售后单已退款完成，不能重复操作");
        }
        // ① 回补库存（已扣的加回可卖；RELEASE 流水备注"售后退款"）
        inventoryService.restoreSold(as.getOrderId(), null, "售后退款，库存回补");
        // ② 支付流水置已退款（幂等）
        paymentRepository.markRefundedByOrder(as.getOrderId());
        // ③ 仅退款且订单未发货 → 关闭订单
        if (as.getType() == TYPE_REFUND_ONLY
                && as.getOrderStatus() != null
                && as.getOrderStatus() == OrderStatusEnum.PAID.getCode()) {
            orderService.refundClose(as.getOrderId(), "售后退款，订单已关闭");
        }
        as.setStatus(STATUS_DONE);
        as.setRefundAt(LocalDateTime.now());
        log.info("售后退款完成: afterSaleNo={}, orderNo={}, amount={}",
                as.getAfterSaleNo(), as.getOrderNo(), as.getAmount());
    }

    // ==================== 私有工具 ====================

    /** 售后单号：AS + 时间戳(14) + 用户ID(4) + 随机(4)，共 22 位（同订单号设计） */
    private String generateNo(Long userId) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String userPart = String.format("%04d", userId == null ? 0 : userId % 10000);
        String randomPart = String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
        return "AS" + timestamp + userPart + randomPart;
    }

    /** 实体 → VO（补中文名；items 由调用方按需附加） */
    private AfterSaleVO toVO(AfterSale as) {
        AfterSaleVO vo = new AfterSaleVO();
        vo.setId(as.getId());
        vo.setAfterSaleNo(as.getAfterSaleNo());
        vo.setOrderId(as.getOrderId());
        vo.setOrderNo(as.getOrderNo());
        vo.setUserId(as.getUserId());
        vo.setUsername(as.getUsername());
        vo.setType(as.getType());
        vo.setTypeText(as.getType() == TYPE_REFUND_ONLY ? "仅退款" : "退货退款");
        vo.setReason(as.getReason());
        vo.setAmount(as.getAmount());
        vo.setStatus(as.getStatus());
        vo.setStatusText(statusText(as.getStatus()));
        vo.setLogisticsNo(as.getLogisticsNo());
        vo.setAuditRemark(as.getAuditRemark());
        vo.setApplyAt(as.getApplyAt());
        vo.setAuditAt(as.getAuditAt());
        vo.setRefundAt(as.getRefundAt());
        vo.setCreatedAt(as.getCreatedAt());
        vo.setOrderStatus(as.getOrderStatus());
        vo.setOrderStatusText(as.getOrderStatus() == null ? "未知" : OrderStatusEnum.textOf(as.getOrderStatus()));
        vo.setOrderTotalAmount(as.getOrderTotalAmount());
        return vo;
    }

    /** 售后状态中文名 */
    private String statusText(Integer status) {
        return switch (status == null ? -1 : status) {
            case STATUS_PENDING -> "待审核";
            case STATUS_WAIT_RETURN -> "待寄回";
            case STATUS_DONE -> "已完成";
            case STATUS_REJECTED -> "已拒绝";
            case STATUS_CANCELLED -> "已撤销";
            default -> "未知";
        };
    }

    /** 订单明细 → 展示 VO（补小计；与订单模块口径一致） */
    private List<OrderItemVO> toItemVOs(List<OrderItem> items) {
        List<OrderItemVO> vos = new ArrayList<>();
        for (OrderItem item : items) {
            OrderItemVO vo = new OrderItemVO();
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
}
