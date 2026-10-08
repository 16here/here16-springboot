package com.mall.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库存流水实体，对应表 inventory_logs。
 *
 * <p>记录每一次库存变化（谁、什么类型、变化多少、关联哪笔订单），
 * 由 {@link com.mall.service.InventoryService} 统一写入。
 *
 * <p>字段语义：
 * <ul>
 *   <li>type：LOCK（下单锁定，可卖减少）/ DEDUCT（支付扣减，真正卖出）/
 *       RELEASE（释放锁定，可卖恢复）/ ADJUST（管理端人工调整）；</li>
 *   <li>change_qty：正数=库存增加方向，负数=库存减少方向；</li>
 *   <li>skuId=0 表示商品级库存（无规格商品直接操作 products.stock）。</li>
 * </ul>
 */
@Data
public class InventoryLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 流水主键 */
    private Long id;

    /** 规格 ID（0=商品级，直接操作 products 库存） */
    private Long skuId;

    /** 商品 ID */
    private Long productId;

    /** 变更数量（正=增加/释放，负=锁定/扣减） */
    private Integer changeQty;

    /** 类型：LOCK/DEDUCT/RELEASE/ADJUST */
    private String type;

    /** 关联订单 ID（人工调整/系统初始化时为 null） */
    private Long orderId;

    /** 订单号（冗余，便于按单号检索） */
    private String orderNo;

    /** 备注（如调整原因、下单锁定） */
    private String remark;

    /** 操作人 ID（SYSTEM 为 null） */
    private Long operatorId;

    /** 流水时间 */
    private LocalDateTime createdAt;

    // ---------- 以下为非表字段（管理端流水查询 JOIN 商品/规格填充） ----------

    /** 商品名称 */
    private String productName;

    /** 规格名称（商品级为 null） */
    private String skuName;

    /** 类型中文名（前端展示） */
    private String typeText;
}
