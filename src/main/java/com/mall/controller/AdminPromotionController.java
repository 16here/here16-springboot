package com.mall.controller;

import com.mall.common.PageResult;
import com.mall.common.Result;
import com.mall.dto.PromotionCreateDTO;
import com.mall.dto.PromotionVO;
import com.mall.service.PromotionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端营销活动接口（阶段 8，/api/admin/promotions/**，仅 ADMIN）。
 *
 * <p>提供：活动 CRUD + 启停（status 切换）。活动为自动参与，无需用户端接口。
 */
@RestController
@RequestMapping("/api/admin/promotions")
@RequiredArgsConstructor
public class AdminPromotionController {

    private final PromotionService promotionService;

    /** 活动列表（分页） */
    @GetMapping
    public Result<PageResult<PromotionVO>> list(@RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(promotionService.adminList(page, pageSize));
    }

    /** 新增活动 */
    @PostMapping
    public Result<PromotionVO> create(@Valid @RequestBody PromotionCreateDTO dto) {
        return Result.success(promotionService.create(dto));
    }

    /** 更新活动 */
    @PutMapping("/{id}")
    public Result<PromotionVO> update(@PathVariable Long id, @Valid @RequestBody PromotionCreateDTO dto) {
        return Result.success(promotionService.update(id, dto));
    }

    /** 删除活动 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        promotionService.delete(id);
        return Result.success(null);
    }

    /** 启停切换（body {status: 1|0}） */
    @PutMapping("/{id}/status")
    public Result<Map<String, Object>> updateStatus(@PathVariable Long id, @RequestBody StatusDTO dto) {
        return Result.success(Map.of("status", promotionService.updateStatus(id, dto.getStatus())));
    }

    /** 状态入参 */
    public static class StatusDTO {
        private Integer status;

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }
    }
}
