package com.mall.controller;

import com.mall.common.PageResult;
import com.mall.common.Result;
import com.mall.dto.CouponCreateDTO;
import com.mall.dto.CouponVO;
import com.mall.service.CouponService;
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
 * 管理端优惠券接口（阶段 8，/api/admin/coupons/**，仅 ADMIN）。
 *
 * <p>提供：模板 CRUD + 定向发放（按手机号给指定用户发券）。
 */
@RestController
@RequestMapping("/api/admin/coupons")
@RequiredArgsConstructor
public class AdminCouponController {

    private final CouponService couponService;

    /** 模板列表（分页） */
    @GetMapping
    public Result<PageResult<CouponVO>> list(@RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(couponService.adminList(page, pageSize));
    }

    /** 新增模板 */
    @PostMapping
    public Result<CouponVO> create(@Valid @RequestBody CouponCreateDTO dto) {
        return Result.success(couponService.create(dto));
    }

    /** 更新模板 */
    @PutMapping("/{id}")
    public Result<CouponVO> update(@PathVariable Long id, @Valid @RequestBody CouponCreateDTO dto) {
        return Result.success(couponService.update(id, dto));
    }

    /** 删除模板 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        couponService.delete(id);
        return Result.success(null);
    }

    /** 定向发放：按手机号给指定用户发 quantity 张券（body {phone, quantity}） */
    @PostMapping("/{id}/issue")
    public Result<Map<String, Object>> issue(@PathVariable Long id,
                                             @RequestBody IssueDTO dto) {
        int quantity = couponService.issue(dto.getPhone(), id, dto.getQuantity());
        return Result.success(Map.of("issued", quantity));
    }

    /** 发放入参（phone + quantity） */
    public static class IssueDTO {
        private String phone;
        private Integer quantity;

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }
    }
}
