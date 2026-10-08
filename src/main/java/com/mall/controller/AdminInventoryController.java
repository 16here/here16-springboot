package com.mall.controller;

import com.mall.common.BusinessException;
import com.mall.common.CurrentUser;
import com.mall.common.PageResult;
import com.mall.common.Result;
import com.mall.dto.InventoryLogVO;
import com.mall.dto.InventorySkuVO;
import com.mall.dto.StockUpdateDTO;
import com.mall.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 库存管理接口（管理端，ADMIN 权限，阶段 6）。
 *
 * <ul>
 *   <li>GET  /api/admin/inventory/skus：库存列表（SKU 逐行 + 无规格商品一行）</li>
 *   <li>POST /api/admin/inventory/adjust：调整库存（delta 可正可负，写 ADJUST 流水）</li>
 *   <li>GET  /api/admin/inventory/logs：库存流水（按 SKU 或商品级查询，分页）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/inventory")
@RequiredArgsConstructor
public class AdminInventoryController {

    private final InventoryService inventoryService;

    /** 库存列表 */
    @GetMapping("/skus")
    public Result<List<InventorySkuVO>> listSkus() {
        return Result.success(inventoryService.listSkus());
    }

    /** 调整库存 */
    @PostMapping("/adjust")
    public Result<Void> adjust(@Valid @RequestBody StockUpdateDTO dto) {
        inventoryService.adjust(dto, currentUserId());
        return Result.success(null);
    }

    /**
     * 库存流水查询（分页）。
     *
     * @param skuId     规格 ID；0 表示商品级（需配合 productId）
     * @param productId 商品 ID（skuId=0 时使用）
     */
    @GetMapping("/logs")
    public Result<PageResult<InventoryLogVO>> logs(@RequestParam(defaultValue = "0") Long skuId,
                                                   @RequestParam(required = false) Long productId,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(inventoryService.queryLogs(skuId, productId, page, pageSize));
    }

    /** 从 Spring Security 上下文提取当前登录用户 ID（与 PaymentController 同法） */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser current)) {
            throw new BusinessException(BusinessException.UNAUTHORIZED, "未登录或登录已过期");
        }
        return current.userId();
    }
}
