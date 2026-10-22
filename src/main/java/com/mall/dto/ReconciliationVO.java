package com.mall.dto;

import com.mall.entity.ReconciliationLog;
import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 对账结果 VO（Phase 3 对账体系）。
 *
 * <p>一次对账的完整结论：核对范围（业务日期/已支付流水数）、
 * 发现问题数、逐条问题明细。管理端展示 + 落库（reconciliation_logs）共用。
 */
@Data
public class ReconciliationVO {

    /** 对账业务日期 */
    private LocalDate bizDate;

    /** 本次核对的已支付流水总数 */
    private long totalPaid;

    /** 核对通过数（无问题的流水） */
    private long checked;

    /** 发现问题数 */
    private long issueCount;

    /** 问题明细（itemType/refId/refNo/issue/status） */
    private List<IssueItem> issues = new ArrayList<>();

    /** 单个对账问题（与 ReconciliationLog 字段对齐，可直接落库/展示） */
    @Data
    public static class IssueItem {
        private String itemType;
        private Long refId;
        private String refNo;
        private String issue;
        private String status;

        public static IssueItem of(String itemType, Long refId, String refNo, String issue) {
            IssueItem item = new IssueItem();
            item.setItemType(itemType);
            item.setRefId(refId);
            item.setRefNo(refNo);
            item.setIssue(issue);
            item.setStatus("OPEN");
            return item;
        }
    }

    /** 从已落库的日志构建结果（管理端查询历史对账用） */
    public static ReconciliationVO fromLogs(LocalDate date, List<ReconciliationLog> logs) {
        ReconciliationVO vo = new ReconciliationVO();
        vo.setBizDate(date);
        vo.setIssueCount(logs.size());
        vo.setIssues(logs.stream().map(l -> {
            IssueItem item = new IssueItem();
            item.setItemType(l.getItemType());
            item.setRefId(l.getRefId());
            item.setRefNo(l.getRefNo());
            item.setIssue(l.getIssue());
            item.setStatus(l.getStatus());
            return item;
        }).toList());
        return vo;
    }
}
