package com.mall.service;

import com.mall.annotation.DS;
import com.mall.dto.ReconciliationVO;
import com.mall.entity.ReconciliationLog;
import com.mall.repository.ReconciliationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 对账服务（Phase 3 对账体系核心）。
 *
 * <p>职责：把<b>支付流水 ↔ 订单 ↔ 库存流水</b>三方数据交叉核对，
 * 发现不一致（脏数据/异常流程/人为篡改）逐条记录，管理端可查询处理。
 *
 * <p>三类对账项：
 * <ul>
 *   <li><b>PAYMENT_ORDER</b>：支付流水存在，但对应订单查不到（幽灵支付）；</li>
 *   <li><b>AMOUNT</b>：支付金额 ≠ 订单应付金额（金额被篡改/计算异常）；</li>
 *   <li><b>INVENTORY</b>：订单已支付，但没有对应的库存扣减（DEDUCT）流水
 *       （库存没扣就放行了，会造成超卖）。</li>
 * </ul>
 *
 * <p>关键设计：
 * <ul>
 *   <li><b>@DS("master")</b>：对账必须读主库——从库有秒级复制延迟，
 *       读从库会把"刚支付成功的流水还没同步到从库"误判为问题；</li>
 *   <li><b>orders 跨片手写 UNION ALL</b>：对账用 JdbcTemplate 直查 SQL，
 *       不走 MyBatis 拦截器，跨分片查询必须显式写 4 张分片（这也是
 *       真实大厂对账系统的常见做法——对账 SQL 独立于业务 SQL）；</li>
 *   <li><b>幂等</b>：同一对账日期重复执行（定时任务 + 管理端手动）不会重复落问题，
 *       先查重再插入；</li>
 *   <li><b>只读不修</b>：对账只发现并记录问题，修复走人工/业务链路，
 *       避免对账器误写污染业务数据。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    /** 对账项类型常量（与 reconciliation_logs.item_type 一致） */
    public static final String TYPE_PAYMENT_ORDER = "PAYMENT_ORDER";
    public static final String TYPE_AMOUNT = "AMOUNT";
    public static final String TYPE_INVENTORY = "INVENTORY";

    private final JdbcTemplate jdbcTemplate;
    private final ReconciliationLogRepository reconciliationLogRepository;

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    /**
     * 执行一次对账。
     *
     * @param bizDate 业务日期；null 时核对全部已支付流水（一般传当天或昨天）
     * @return 对账结果（含问题明细，管理端直接展示）
     */
    @DS("master")
    @Transactional
    public ReconciliationVO run(LocalDate bizDate) {
        LocalDate date = bizDate == null ? LocalDate.now() : bizDate;
        ReconciliationVO vo = new ReconciliationVO();
        vo.setBizDate(date);

        // 1. 查业务日期内全部"已支付"支付流水（payments 不分表）
        String paidSql = "SELECT id, payment_no, order_id, amount FROM payments WHERE status = 1";
        if (bizDate != null) {
            paidSql += " AND paid_at >= '" + date.atStartOfDay() + "'"
                    + " AND paid_at < '" + date.plusDays(1).atStartOfDay() + "'";
        }
        List<Map<String, Object>> paid = jdbcTemplate.queryForList(paidSql);
        vo.setTotalPaid(paid.size());

        // 2. 逐笔三方核对
        for (Map<String, Object> row : paid) {
            Long paymentId = ((Number) row.get("id")).longValue();
            String paymentNo = (String) row.get("payment_no");
            Long orderId = ((Number) row.get("order_id")).longValue();
            BigDecimal amount = new BigDecimal(row.get("amount").toString());

            // 2.1 订单存在性 + 金额：跨 4 张订单分片 UNION ALL 定位
            Map<String, Object> order = findOrder(orderId);

            if (order == null) {
                // 幽灵支付：支付流水在，订单没了
                addIssue(vo, date, TYPE_PAYMENT_ORDER, paymentId, paymentNo,
                        "支付流水存在但订单不存在：orderId=" + orderId);
                continue;
            }
            BigDecimal orderAmount = new BigDecimal(order.get("total_amount").toString());
            if (amount.compareTo(orderAmount) != 0) {
                addIssue(vo, date, TYPE_AMOUNT, orderId, (String) order.get("order_no"),
                        "支付金额(" + amount + ")与订单金额(" + orderAmount + ")不一致，paymentNo=" + paymentNo);
                continue;
            }

            // 2.2 已支付订单必须已扣库存（DEDUCT 流水；月分表按业务日期当月）
            if (countDeductLogs(orderId, date) == 0) {
                addIssue(vo, date, TYPE_INVENTORY, orderId, (String) order.get("order_no"),
                        "订单已支付但缺少库存扣减(DEDUCT)流水，存在超卖风险");
                continue;
            }
            vo.setChecked(vo.getChecked() + 1);
        }

        vo.setIssueCount(vo.getIssues().size());
        log.info("对账完成: bizDate={}, 已支付流水={}, 通过={}, 问题={}",
                date, vo.getTotalPaid(), vo.getChecked(), vo.getIssueCount());
        return vo;
    }

    /**
     * 跨 4 张订单分片查订单（对账专用，不走 MyBatis 拦截器，
     * 显式 UNION ALL 保证覆盖全部分片）。
     *
     * @return 订单行 {id, order_no, total_amount}；不存在返回 null
     */
    private Map<String, Object> findOrder(Long orderId) {
        String sql = """
                SELECT id, order_no, total_amount FROM orders_0 WHERE id = ?
                UNION ALL SELECT id, order_no, total_amount FROM orders_1 WHERE id = ?
                UNION ALL SELECT id, order_no, total_amount FROM orders_2 WHERE id = ?
                UNION ALL SELECT id, order_no, total_amount FROM orders_3 WHERE id = ?
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, orderId, orderId, orderId, orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 查订单在某业务日期当月的库存扣减流水数（月分表名 = inventory_logs_yyyyMM）。
     * 支付成功写入的 DEDUCT 流水就在当月表里。
     */
    private long countDeductLogs(Long orderId, LocalDate date) {
        String table = "inventory_logs_" + date.format(MONTH);
        // 表可能不存在（当月无任何库存流水）——先探测再查，避免 SQL 报错
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                Integer.class, table);
        if (exists == null || exists == 0) {
            return 0;
        }
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE order_id = ? AND type = 'DEDUCT'",
                Long.class, orderId);
    }

    /**
     * 落一条对账问题：先查重（同日期+类型+业务ID）防重复落库（幂等），
     * 新问题插入 reconciliation_logs 并追加到返回 VO。
     */
    private void addIssue(ReconciliationVO vo, LocalDate date, String type,
                          Long refId, String refNo, String issue) {
        long exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_logs WHERE biz_date = ? AND item_type = ? AND ref_id = ?",
                Long.class, date, type, refId);
        if (exists > 0) {
            return; // 上次对账已记录，本次跳过（幂等）
        }
        ReconciliationLog logEntry = new ReconciliationLog();
        logEntry.setBizDate(date);
        logEntry.setItemType(type);
        logEntry.setRefId(refId);
        logEntry.setRefNo(refNo);
        logEntry.setIssue(issue);
        logEntry.setStatus("OPEN");
        reconciliationLogRepository.insert(logEntry);
        vo.getIssues().add(ReconciliationVO.IssueItem.of(type, refId, refNo, issue));
    }

    /** 管理端：按条件分页查对账日志（含历史问题处理状态） */
    public com.mall.common.PageResult<ReconciliationLog> logs(LocalDate bizDate, String itemType,
                                                              String status, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        long total = reconciliationLogRepository.count(bizDate, itemType, status);
        List<ReconciliationLog> list = reconciliationLogRepository.page(bizDate, itemType, status, offset, pageSize);
        return new com.mall.common.PageResult<>(total, list);
    }
}
