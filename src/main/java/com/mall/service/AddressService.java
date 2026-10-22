package com.mall.service;

import com.mall.annotation.DS;
import com.mall.common.BusinessException;
import com.mall.common.SensitiveUtil;
import com.mall.dto.AddressDTO;
import com.mall.dto.AddressVO;
import com.mall.entity.Address;
import com.mall.repository.AddressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 收货地址服务：列表、新增、修改、删除、设置默认。
 *
 * <p>设计要点：
 * <ul>
 *   <li>每人至多一个默认地址：设置默认 = "先全部置 0 再置目标 1"（事务内）；</li>
 *   <li>第一个地址自动设为默认（省得用户再设一次）；</li>
 *   <li>删除默认地址后，自动把剩余最早创建的地址设为默认；</li>
 *   <li>所有按 id 的操作都校验归属（地址是用户私有数据）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AddressService {

    private final AddressRepository addressRepository;
    // Phase 1：收货手机号加密存储（入库加密、展示解密，防库泄露）
    private final SensitiveUtil sensitiveUtil;

    /** 查询我的地址列表（默认排最前）。强制主库读：新增/设默认后立即进确认订单页要能看到。 */
    @DS("master")
    public List<AddressVO> list(Long userId) {
        List<Address> addresses = addressRepository.selectByUserId(userId);
        List<AddressVO> vos = new ArrayList<>();
        for (Address a : addresses) {
            vos.add(toVO(a));
        }
        return vos;
    }

    /** 新增地址：首个地址自动设为默认；勾选"设为默认"则替换当前默认 */
    @Transactional
    public AddressVO create(Long userId, AddressDTO dto) {
        long count = addressRepository.countByUserId(userId);
        boolean asDefault = (dto.getIsDefault() != null && dto.getIsDefault()) || count == 0;

        if (asDefault) {
            // 先把该用户所有地址置为非默认，再插入默认地址
            addressRepository.clearDefault(userId);
        }

        Address address = new Address();
        address.setUserId(userId);
        fill(address, dto);
        address.setIsDefault(asDefault ? 1 : 0);
        addressRepository.insert(address);

        return toVO(address);
    }

    /** 修改地址（不允许改归属；勾选"设为默认"时同步替换默认） */
    @Transactional
    public AddressVO update(Long userId, Long id, AddressDTO dto) {
        if (addressRepository.update(id, userId, toEntity(dto)) == 0) {
            throw new BusinessException(BusinessException.NOT_FOUND, "收货地址不存在");
        }
        if (dto.getIsDefault() != null && dto.getIsDefault()) {
            addressRepository.clearDefault(userId);
            addressRepository.setDefault(id, userId);
        }
        return toVO(addressRepository.findByIdAndUserId(id, userId));
    }

    /** 删除地址：删除默认地址后，自动把剩余最早创建的地址设为默认 */
    @Transactional
    public void delete(Long userId, Long id) {
        Address existed = addressRepository.findByIdAndUserId(id, userId);
        if (existed == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "收货地址不存在");
        }
        addressRepository.deleteByIdAndUserId(id, userId);

        // 删的是默认地址且还有剩余 → 把剩余第一个（最早创建）设为默认
        if (existed.getIsDefault() != null && existed.getIsDefault() == 1) {
            List<Address> remaining = addressRepository.selectByUserId(userId);
            if (!remaining.isEmpty()) {
                addressRepository.clearDefault(userId);
                addressRepository.setDefault(remaining.get(0).getId(), userId);
            }
        }
    }

    /** 设置默认地址：先清场再置目标（事务保证唯一默认） */
    @Transactional
    public void setDefault(Long userId, Long id) {
        if (addressRepository.findByIdAndUserId(id, userId) == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "收货地址不存在");
        }
        addressRepository.clearDefault(userId);
        addressRepository.setDefault(id, userId);
    }

    /** 实体 → VO（拼接完整地址，前端直接展示；手机号解密） */
    private AddressVO toVO(Address a) {
        AddressVO vo = new AddressVO();
        vo.setId(a.getId());
        vo.setReceiverName(a.getReceiverName());
        vo.setReceiverPhone(sensitiveUtil.decrypt(a.getReceiverPhone()));
        vo.setProvince(a.getProvince());
        vo.setCity(a.getCity());
        vo.setDistrict(a.getDistrict());
        vo.setDetail(a.getDetail());
        vo.setIsDefault(a.getIsDefault());
        vo.setFullAddress(a.getProvince() + a.getCity() + a.getDistrict() + a.getDetail());
        return vo;
    }

    /** DTO → 实体（update 场景；insert 场景在 create 里单独填充） */
    private Address toEntity(AddressDTO dto) {
        Address a = new Address();
        fill(a, dto);
        return a;
    }

    private void fill(Address a, AddressDTO dto) {
        a.setReceiverName(dto.getReceiverName());
        // 收货手机号加密存储（Phase 1：库内不可见明文，展示时在 toVO 解密）
        a.setReceiverPhone(sensitiveUtil.encrypt(dto.getReceiverPhone()));
        a.setProvince(dto.getProvince());
        a.setCity(dto.getCity());
        a.setDistrict(dto.getDistrict());
        a.setDetail(dto.getDetail());
    }
}
