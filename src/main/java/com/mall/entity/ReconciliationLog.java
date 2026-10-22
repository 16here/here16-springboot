package com.mall.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对账日志实体（Phase 3 对账体系），对应 reconciliation_logs 表。
 *
 * <p>每次对账把发现的问题逐条落库，管理端可查询、可复核：
 * <ul>
 *   <li>item_type：对账项类型（PAYMENT_ORDER=支付流水订单不存在 /
 *       AMOUNT=支付金额与订单金额不一致 / INVENTORY=已支付订单缺库存扣减流水）；</li>
 *   <li>status：OPEN=待处理（未人工核实）→ IGNORED=已人工确认忽略（历史遗留等）；</li>
 *   <li>ref_id / ref_no：关联的业务主键（订单 ID / 支付流水号），便于定位原始数据。</li>
 * </ul>
 */
@Data
public class ReconciliationLog {

    /** 主键 */
    private Long id;

    /** 对账日期（业务日期，yyyy-MM-dd） */
    private LocalDate bizDate;

    /** 对账项类型：PAYMENT_ORDER / AMOUNT / INVENTORY */
    private String itemType;

    /** 关联业务 ID（订单 ID / 支付流水 ID） */
    private Long refId;

    /** 关联业务编号（订单号 / 支付流水号，便于人工检索） */
    private String refNo;

    /** 问题描述（给管理员看的可读文案） */
    private String issue;

    /** 状态：OPEN=待处理 IGNORED=已忽略 */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
