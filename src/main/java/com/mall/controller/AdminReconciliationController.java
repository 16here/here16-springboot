package com.mall.controller;

import com.mall.common.PageResult;
import com.mall.common.Result;
import com.mall.dto.ReconciliationVO;
import com.mall.entity.ReconciliationLog;
import com.mall.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 管理端对账控制器（Phase 3 对账体系）。
 *
 * <p>三个能力：
 * <ul>
 *   <li>{@code POST /run}：手动触发一次对账（默认今天，可指定业务日期）——
 *       日常验证 + 定时任务失败后的补跑通道；</li>
 *   <li>{@code GET /logs}：分页查询对账日志（按日期/类型/状态筛选）；</li>
 *   <li>{@code GET /latest}：最近一次对账结果摘要（管理端首页对账看板用）。</li>
 * </ul>
 *
 * <p>权限：/api/admin/** 由 SecurityConfig 统一限制 ADMIN 角色。
 */
@RestController
@RequestMapping("/api/admin/reconciliation")
@RequiredArgsConstructor
public class AdminReconciliationController {

    private final ReconciliationService reconciliationService;

    /** 手动触发对账（bizDate 可空，默认今天） */
    @PostMapping("/run")
    public Result<ReconciliationVO> run(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        return Result.success(reconciliationService.run(bizDate));
    }

    /** 对账日志分页查询（历史问题 + 处理状态） */
    @GetMapping("/logs")
    public Result<PageResult<ReconciliationLog>> logs(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate,
            @RequestParam(required = false) String itemType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(reconciliationService.logs(bizDate, itemType, status, page, pageSize));
    }

    /** 最近一次对账结果（有记录才返回） */
    @GetMapping("/latest")
    public Result<ReconciliationVO> latest() {
        var logs = reconciliationService.logs(null, null, null, 1, 1000);
        if (logs.getList().isEmpty()) {
            return Result.success(new ReconciliationVO());
        }
        return Result.success(ReconciliationVO.fromLogs(
                logs.getList().get(0).getBizDate(), logs.getList()));
    }
}
