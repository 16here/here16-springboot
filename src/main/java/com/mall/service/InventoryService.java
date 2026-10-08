package com.mall.service;

import com.mall.common.BusinessException;
import com.mall.common.PageResult;
import com.mall.dto.InventoryLogVO;
import com.mall.dto.InventorySkuVO;
import com.mall.dto.StockUpdateDTO;
import com.mall.entity.InventoryLog;
import com.mall.entity.OrderItem;
import com.mall.entity.Product;
import com.mall.entity.ProductSku;
import com.mall.repository.InventoryLogRepository;
import com.mall.repository.OrderItemRepository;
import com.mall.repository.ProductRepository;
import com.mall.repository.ProductSkuRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 库存服务（阶段 6 核心）：锁库存 / 支付扣减 / 释放锁定 / 管理端调整 / 流水查询。
 *
 * <p>库存模型升级为<b>预占式</b>（淘宝同款）：
 * <pre>
 *   下单（待付款）   → LOCK    locked_stock += qty（可卖 = stock - locked_stock 减少）
 *   支付成功（0→1）  → DEDUCT  stock -= qty, locked_stock -= qty（正式卖出）
 *   取消/关闭/超时   → RELEASE locked_stock -= qty（可卖恢复）
 *   管理端人工调整   → ADJUST  stock += delta
 * </pre>
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>数据库条件 UPDATE 是最终防线</b>：锁定/扣减/释放都带条件（可卖/锁定足量），
 *       并发下不会超卖、不会重复释放；</li>
 *   <li><b>Redis 预减是快速拦截</b>：下单前先 Redis 减，不足直接拒绝，减少数据库压力；
 *       Redis 异常自动降级（见 {@link RedisStockService}）；</li>
 *   <li><b>每次变更写流水</b>：管理端可追溯每一次库存变化（谁、什么类型、关联哪单）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    /** 流水类型 */
    public static final String TYPE_LOCK = "LOCK";        // 下单锁定（可卖减少）
    public static final String TYPE_DEDUCT = "DEDUCT";    // 支付扣减（正式卖出）
    public static final String TYPE_RELEASE = "RELEASE";  // 释放锁定（可卖恢复）
    public static final String TYPE_ADJUST = "ADJUST";    // 管理端人工调整

    private final ProductSkuRepository productSkuRepository;
    private final ProductRepository productRepository;
    private final InventoryLogRepository inventoryLogRepository;
    private final OrderItemRepository orderItemRepository;
    private final RedisStockService redisStockService;

    // ==================== 订单链路 ====================

    /**
     * 下单锁库存（LOCK，订单创建事务内调用）。
     *
     * <p>流程：先 Redis 预减（快速拦截超卖，异常降级）→ 数据库条件锁定 →
     * 写 LOCK 流水。数据库锁定失败（可卖不足/并发抢空）时回补 Redis 并抛异常
     * （订单事务整体回滚）。
     *
     * @param orderId  订单 ID（流水关联）
     * @param orderNo  订单号（流水冗余字段）
     * @param operatorId 下单用户 ID（流水记录"谁"）
     * @param items    订单明细（含 skuId/productId/quantity）
     */
    @Transactional
    public void lock(Long orderId, String orderNo, Long operatorId, List<OrderItem> items) {
        for (OrderItem item : items) {
            int qty = item.getQuantity();
            if (item.getSkuId() != null && item.getSkuId() > 0) {
                String key = RedisStockService.skuKey(item.getSkuId());
                // 1. Redis 预减（防超卖第一道闸；异常降级放行）
                if (!redisStockService.preDeduct(key, qty)) {
                    throw new BusinessException(BusinessException.PARAM_ERROR,
                            "「" + item.getProductName() + "」库存不足（实时库存已被抢完）");
                }
                // 2. 数据库原子锁定（最终防线）
                if (productSkuRepository.lockStock(item.getSkuId(), qty) == 0) {
                    redisStockService.rollback(key, qty); // 回补 Redis
                    throw new BusinessException(BusinessException.PARAM_ERROR,
                            "「" + item.getProductName() + "」库存不足，请重试");
                }
                writeLog(item.getSkuId(), item.getProductId(), -qty, TYPE_LOCK,
                        orderId, orderNo, "下单锁定库存", operatorId);
            } else {
                String key = RedisStockService.productKey(item.getProductId());
                if (!redisStockService.preDeduct(key, qty)) {
                    throw new BusinessException(BusinessException.PARAM_ERROR,
                            "「" + item.getProductName() + "」库存不足（实时库存已被抢完）");
                }
                if (productRepository.lockStock(item.getProductId(), qty) == 0) {
                    redisStockService.rollback(key, qty);
                    throw new BusinessException(BusinessException.PARAM_ERROR,
                            "「" + item.getProductName() + "」库存不足，请重试");
                }
                writeLog(0L, item.getProductId(), -qty, TYPE_LOCK,
                        orderId, orderNo, "下单锁定库存", operatorId);
            }
        }
        log.info("订单锁库存完成: orderNo={}, items={}", orderNo, items.size());
    }

    /**
     * 支付扣减库存（DEDUCT，支付回调 markPaid 时调用）。
     * 把下单时锁定的库存正式卖出；幂等由订单状态机保证（仅 0→1 执行一次）。
     */
    @Transactional
    public void deduct(Long orderId) {
        for (OrderItem item : orderItemsOf(orderId)) {
            int qty = item.getQuantity();
            if (item.getSkuId() != null && item.getSkuId() > 0) {
                if (productSkuRepository.deductLocked(item.getSkuId(), qty) == 0) {
                    log.warn("支付扣减库存未命中（订单可能未锁定/已扣）：orderId={}, skuId={}",
                            orderId, item.getSkuId());
                    continue;
                }
                writeLog(item.getSkuId(), item.getProductId(), -qty, TYPE_DEDUCT,
                        orderId, null, "支付成功，正式扣减库存", null);
            } else {
                if (productRepository.deductLocked(item.getProductId(), qty) == 0) {
                    log.warn("支付扣减库存未命中（商品级）：orderId={}, productId={}",
                            orderId, item.getProductId());
                    continue;
                }
                writeLog(0L, item.getProductId(), -qty, TYPE_DEDUCT,
                        orderId, null, "支付成功，正式扣减库存", null);
            }
        }
        log.info("订单支付扣减库存完成: orderId={}", orderId);
    }

    /**
     * 释放锁定库存（RELEASE，取消/关闭/超时调用）。
     * 把未支付的预占库存释放回可卖，并回补 Redis。
     *
     * @param orderId    订单 ID
     * @param operatorType 操作方（USER/ADMIN/SYSTEM，仅用于流水展示，这里不落表）
     * @param operatorId 操作人 ID（系统为 null）
     * @param remark     备注（如"用户取消订单"）
     */
    @Transactional
    public void release(Long orderId, Long operatorId, String remark) {
        for (OrderItem item : orderItemsOf(orderId)) {
            int qty = item.getQuantity();
            if (item.getSkuId() != null && item.getSkuId() > 0) {
                if (productSkuRepository.releaseLocked(item.getSkuId(), qty) == 0) {
                    log.warn("释放锁定库存未命中（可能未锁定/已释放）：orderId={}, skuId={}",
                            orderId, item.getSkuId());
                    continue;
                }
                redisStockService.rollback(RedisStockService.skuKey(item.getSkuId()), qty);
                writeLog(item.getSkuId(), item.getProductId(), qty, TYPE_RELEASE,
                        orderId, null, remark, operatorId);
            } else {
                if (productRepository.releaseLocked(item.getProductId(), qty) == 0) {
                    log.warn("释放锁定库存未命中（商品级）：orderId={}, productId={}",
                            orderId, item.getProductId());
                    continue;
                }
                redisStockService.rollback(RedisStockService.productKey(item.getProductId()), qty);
                writeLog(0L, item.getProductId(), qty, TYPE_RELEASE,
                        orderId, null, remark, operatorId);
            }
        }
        log.info("订单释放锁定库存完成: orderId={}", orderId);
    }

    /**
     * 回补已扣库存（已支付但未发货的订单取消/退款时调用，阶段 6）。
     *
     * <p>与 {@link #release} 的区别：待付款订单的库存还在 locked_stock 里，释放即可；
     * 而已支付订单的库存已经正式扣减（DEDUCT），取消时需要把 stock 加回来
     * （相当于退货入库），同时回补 Redis 可卖。
     */
    @Transactional
    public void restoreSold(Long orderId, Long operatorId, String remark) {
        for (OrderItem item : orderItemsOf(orderId)) {
            int qty = item.getQuantity();
            if (item.getSkuId() != null && item.getSkuId() > 0) {
                productSkuRepository.restoreStock(item.getSkuId(), qty);
                redisStockService.rollback(RedisStockService.skuKey(item.getSkuId()), qty);
                writeLog(item.getSkuId(), item.getProductId(), qty, TYPE_RELEASE,
                        orderId, null, remark, operatorId);
            } else {
                productRepository.restoreStock(item.getProductId(), qty);
                redisStockService.rollback(RedisStockService.productKey(item.getProductId()), qty);
                writeLog(0L, item.getProductId(), qty, TYPE_RELEASE,
                        orderId, null, remark, operatorId);
            }
        }
        log.info("订单已支付库存回补完成: orderId={}", orderId);
    }

    /**
     * 兜底释放（InventoryReleaseTask 定时调用，幂等）。
     *
     * <p>场景：OrderTimeoutTask 因异常/并发跳过、或订单状态已变但库存仍锁定，
     * 本方法按订单检查：<b>已有 RELEASE 流水（已释放）或从未 LOCK（没锁过）则跳过</b>，
     * 避免重复释放导致可卖库存虚增。
     */
    @Transactional
    public void releaseIfLocked(Long orderId) {
        List<InventoryLog> logs = inventoryLogRepository.selectByOrderId(orderId);
        boolean locked = logs.stream().anyMatch(l -> TYPE_LOCK.equals(l.getType()));
        boolean released = logs.stream().anyMatch(l -> TYPE_RELEASE.equals(l.getType()));
        if (!locked || released) {
            log.debug("兜底释放跳过：orderId={}, locked={}, released={}", orderId, locked, released);
            return;
        }
        release(orderId, null, "超时未支付，库存兜底释放");
        log.info("库存兜底释放完成: orderId={}", orderId);
    }

    // ==================== 管理端 ====================

    /**
     * 管理端库存列表：有规格商品逐规格一行 + 无规格商品一行。
     */
    public List<InventorySkuVO> listSkus() {
        List<InventorySkuVO> vos = new ArrayList<>();
        for (ProductSku sku : productSkuRepository.selectAllWithProduct()) {
            InventorySkuVO vo = new InventorySkuVO();
            vo.setSkuId(sku.getId());
            vo.setProductId(sku.getProductId());
            vo.setProductName(sku.getProductName());
            vo.setProductSku(sku.getProductSku());
            vo.setSkuName(sku.getName());
            vo.setPrice(sku.getPrice());
            vo.setStock(sku.getStock());
            vo.setLockedStock(sku.getLockedStock() == null ? 0 : sku.getLockedStock());
            vo.setAvailable(sku.getAvailable() == null ? 0 : sku.getAvailable());
            vo.setStatus(sku.getProductStatus());
            vos.add(vo);
        }
        for (Product p : productRepository.selectNoSkuProducts()) {
            InventorySkuVO vo = new InventorySkuVO();
            vo.setSkuId(0L);
            vo.setProductId(p.getId());
            vo.setProductName(p.getName());
            vo.setProductSku(p.getSku());
            vo.setSkuName("默认");
            vo.setPrice(p.getPrice());
            vo.setStock(p.getStock());
            vo.setLockedStock(p.getLockedStock() == null ? 0 : p.getLockedStock());
            vo.setAvailable(p.getAvailable() == null ? 0 : p.getAvailable());
            vo.setStatus(p.getStatus());
            vos.add(vo);
        }
        return vos;
    }

    /**
     * 管理端调整库存（ADJUST）。
     *
     * @param dto        { skuId, productId(可选), delta, remark }
     * @param operatorId 操作管理员 ID
     */
    @Transactional
    public void adjust(StockUpdateDTO dto, Long operatorId) {
        if (dto.getDelta() == null || dto.getDelta() == 0) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "调整数量不能为 0");
        }
        String remark = dto.getRemark() == null || dto.getRemark().isBlank()
                ? "管理端调整库存" : dto.getRemark().trim();
        Long skuId = dto.getSkuId();

        if (skuId != null && skuId > 0) {
            ProductSku sku = productSkuRepository.findById(skuId);
            if (sku == null) {
                throw new BusinessException(BusinessException.NOT_FOUND, "规格不存在");
            }
            if (productSkuRepository.updateStock(skuId, dto.getDelta()) == 0) {
                throw new BusinessException(BusinessException.PARAM_ERROR, "调整后库存不能为负");
            }
            // 同步 Redis 实时库存（可卖 = stock - locked_stock）
            redisStockService.refresh(RedisStockService.skuKey(skuId),
                    sku.getStock() - (sku.getLockedStock() == null ? 0 : sku.getLockedStock()) + dto.getDelta());
            writeLog(skuId, sku.getProductId(), dto.getDelta(), TYPE_ADJUST,
                    null, null, remark, operatorId);
            log.info("SKU 库存调整: skuId={}, delta={}, by={}", skuId, dto.getDelta(), operatorId);
        } else {
            // 商品级（无规格商品）
            if (dto.getProductId() == null) {
                throw new BusinessException(BusinessException.PARAM_ERROR, "商品级库存调整需传商品 ID");
            }
            Product p = productRepository.findById(dto.getProductId());
            if (p == null) {
                throw new BusinessException(BusinessException.NOT_FOUND, "商品不存在");
            }
            if (productRepository.updateStock(p.getId(), dto.getDelta()) == 0) {
                throw new BusinessException(BusinessException.PARAM_ERROR, "调整后库存不能为负");
            }
            redisStockService.refresh(RedisStockService.productKey(p.getId()),
                    p.getStock() - (p.getLockedStock() == null ? 0 : p.getLockedStock()) + dto.getDelta());
            writeLog(0L, p.getId(), dto.getDelta(), TYPE_ADJUST,
                    null, null, remark, operatorId);
            log.info("商品级库存调整: productId={}, delta={}, by={}", p.getId(), dto.getDelta(), operatorId);
        }
    }

    /**
     * 库存流水分页（管理端查看）。
     *
     * @param skuId   规格 ID；0 表示商品级（需传 productId 定位商品）
     * @param productId 商品 ID（skuId=0 时使用）
     */
    public PageResult<InventoryLogVO> queryLogs(Long skuId, Long productId, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<InventoryLog> logs;
        long total;
        if (skuId != null && skuId > 0) {
            logs = inventoryLogRepository.selectBySkuId(skuId, offset, pageSize);
            total = inventoryLogRepository.countBySkuId(skuId);
        } else {
            Long pid = productId == null ? 0L : productId;
            logs = inventoryLogRepository.selectByProductId(pid, offset, pageSize);
            total = inventoryLogRepository.countByProductId(pid);
        }
        List<InventoryLogVO> vos = new ArrayList<>();
        for (InventoryLog log : logs) {
            vos.add(toVO(log));
        }
        return new PageResult<>(total, vos);
    }

    // ==================== 私有工具 ====================

    /** 查订单明细（锁/扣/释放共用） */
    private List<OrderItem> orderItemsOf(Long orderId) {
        return orderItemRepository.selectByOrderId(orderId);
    }

    /** 写一条库存流水 */
    private void writeLog(Long skuId, Long productId, int changeQty, String type,
                          Long orderId, String orderNo, String remark, Long operatorId) {
        InventoryLog log = new InventoryLog();
        log.setSkuId(skuId);
        log.setProductId(productId);
        log.setChangeQty(changeQty);
        log.setType(type);
        log.setOrderId(orderId);
        log.setOrderNo(orderNo);
        log.setRemark(remark);
        log.setOperatorId(operatorId);
        inventoryLogRepository.insert(log);
    }

    /** 实体 → VO（补类型中文名） */
    private InventoryLogVO toVO(InventoryLog log) {
        InventoryLogVO vo = new InventoryLogVO();
        vo.setId(log.getId());
        vo.setSkuId(log.getSkuId());
        vo.setProductId(log.getProductId());
        vo.setProductName(log.getProductName());
        vo.setSkuName(log.getSkuName());
        vo.setChangeQty(log.getChangeQty());
        vo.setType(log.getType());
        vo.setTypeText(typeText(log.getType()));
        vo.setOrderNo(log.getOrderNo());
        vo.setRemark(log.getRemark());
        // 操作方：ADJUST 且 operatorId 有值 → 管理员；LOCK → 用户；其余按 type 推断
        if (TYPE_ADJUST.equals(log.getType())) {
            vo.setOperatorText(log.getOperatorId() == null ? "管理员" : "管理员");
        } else if (TYPE_RELEASE.equals(log.getType()) || TYPE_LOCK.equals(log.getType())) {
            vo.setOperatorText(log.getOperatorId() == null ? "系统" : "用户");
        } else {
            vo.setOperatorText("系统");
        }
        vo.setCreatedAt(log.getCreatedAt());
        return vo;
    }

    /** 类型中文名 */
    private String typeText(String type) {
        return switch (type) {
            case TYPE_LOCK -> "下单锁定";
            case TYPE_DEDUCT -> "支付扣减";
            case TYPE_RELEASE -> "释放锁定";
            case TYPE_ADJUST -> "人工调整";
            default -> type;
        };
    }
}
