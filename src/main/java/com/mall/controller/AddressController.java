package com.mall.controller;

import com.mall.common.BusinessException;
import com.mall.common.CurrentUser;
import com.mall.common.Result;
import com.mall.dto.AddressDTO;
import com.mall.dto.AddressVO;
import com.mall.service.AddressService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收货地址接口（/api/addresses/**，需登录）。
 *
 * <p>地址是用户私有数据：userId 一律从登录上下文取，不接受前端传参。
 */
@RestController
@RequestMapping("/api/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    /** 地址列表（默认排最前） */
    @GetMapping
    public Result<List<AddressVO>> list() {
        return Result.success(addressService.list(currentUserId()));
    }

    /** 新增地址（首个自动设为默认） */
    @PostMapping
    public Result<AddressVO> create(@Valid @RequestBody AddressDTO dto) {
        return Result.success(addressService.create(currentUserId(), dto));
    }

    /** 修改地址 */
    @PutMapping("/{id}")
    public Result<AddressVO> update(@PathVariable Long id, @Valid @RequestBody AddressDTO dto) {
        return Result.success(addressService.update(currentUserId(), id, dto));
    }

    /** 删除地址 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        addressService.delete(currentUserId(), id);
        return Result.success();
    }

    /** 设为默认地址 */
    @PutMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        addressService.setDefault(currentUserId(), id);
        return Result.success();
    }

    /** 从 Spring Security 上下文提取当前登录用户 ID */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser current)) {
            throw new BusinessException(BusinessException.UNAUTHORIZED, "未登录或登录已过期");
        }
        return current.userId();
    }
}
