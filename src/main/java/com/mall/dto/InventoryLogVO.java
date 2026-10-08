package com.mall.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存流水视图对象（管理端"查看流水"展示）。
 */
@Data
public class InventoryLogVO {

    /** 流水主键 */
    private Long id;

    /** 规格 ID（0=商品级） */
    private Long skuId;

    /** 商品 ID */
    private Long productId;

    /** 商品名称 */
    private String productName;

    /** 规格名称（商品级为 null） */
    private String skuName;

    /** 变更数量（正=增加/释放，负=锁定/扣减） */
    private Integer changeQty;

    /** 类型：LOCK/DEDUCT/RELEASE/ADJUST */
    private String type;

    /** 类型中文名（前端展示） */
    private String typeText;

    /** 关联订单号（人工调整为 null） */
    private String orderNo;

    /** 备注 */
    private String remark;

    /** 操作方（USER/ADMIN/SYSTEM 由 operatorId + type 推导，VO 里直接给中文/英文标签） */
    private String operatorText;

    /** 流水时间 */
    private LocalDateTime createdAt;
}
