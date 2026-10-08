package com.mall.service;

import com.mall.common.BusinessException;
import com.mall.common.PageResult;
import com.mall.dto.CouponCreateDTO;
import com.mall.dto.CouponVO;
import com.mall.dto.UserCouponVO;
import com.mall.entity.Coupon;
import com.mall.entity.User;
import com.mall.entity.UserCoupon;
import com.mall.repository.CouponRepository;
import com.mall.repository.UserCouponRepository;
import com.mall.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 优惠券服务（阶段 8）：领券、我的券、可用券计算、锁定/核销/释放、管理端 CRUD 与发放。
 *
 * <p>券的生命周期（与订单联动）：
 * <pre>
 *   领取(0 未使用) → 下单锁定(1，绑定订单) → 支付成功核销(2，永久使用)
 *   下单后取消/关闭/超时 → 释放回(0)，券可再次使用
 *   超过有效期未使用 → 视为已过期(3，展示用)
 * </pre>
 *
 * <p>优惠计算口径（与 OrderService 配合）：
 * <ul>
 *   <li>门槛基于<b>商品原价合计</b>（originalAmount，活动优惠前）；</li>
 *   <li>券优惠作用于<b>活动优惠后的金额</b>（original - promotion），最低支付 0；</li>
 *   <li>满减券：减 amount；折扣券：金额 × discountRate。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    /** 用户券状态 */
    public static final int STATUS_UNUSED = 0;   // 未使用
    public static final int STATUS_LOCKED = 1;   // 已锁定（下单未支付）
    public static final int STATUS_USED = 2;     // 已使用（核销）
    public static final int STATUS_EXPIRED = 3;  // 已过期

    private final CouponRepository couponRepository;
    private final UserCouponRepository userCouponRepository;
    private final UserRepository userRepository;

    // ==================== 用户端 ====================

    /**
     * 领券中心列表：上架 + 未过期的券模板，附带已领取数与"当前用户是否已领"。
     * userId 可空（未登录也能看领券中心，领取时才要求登录）。
     */
    public List<CouponVO> listCenter(Long userId) {
        List<Coupon> coupons = couponRepository.selectCenter();
        List<CouponVO> vos = new ArrayList<>();
        for (Coupon c : coupons) {
            CouponVO vo = toVO(c);
            vo.setStatusText(c.getStatus() == 1 ? "上架中" : "已下架");
            if (userId != null) {
                vo.setReceived(userCouponRepository.countReceived(c.getId(), userId) > 0);
            } else {
                vo.setReceived(false);
            }
            vos.add(vo);
        }
        return vos;
    }

    /**
     * 领取优惠券。
     * 校验链：模板存在 → 上架 → 在有效期内 → 未领完（发行总量）→ 未超每人限领。
     */
    @Transactional
    public UserCouponVO receive(Long userId, Long couponId) {
        Coupon coupon = couponRepository.selectById(couponId);
        if (coupon == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "优惠券不存在");
        }
        if (coupon.getStatus() == null || coupon.getStatus() != 1) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "该优惠券已下架");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.getValidFrom()) || now.isAfter(coupon.getValidUntil())) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "该优惠券不在领取时间内");
        }
        // 发行总量校验（0=不限量）
        if (coupon.getTotalQuantity() != null && coupon.getTotalQuantity() > 0) {
            long received = couponRepository.countReceived(couponId);
            if (received >= coupon.getTotalQuantity()) {
                throw new BusinessException(BusinessException.PARAM_ERROR, "该优惠券已领完");
            }
        }
        // 每人限领校验
        int limit = coupon.getPerUserLimit() == null ? 1 : coupon.getPerUserLimit();
        long mine = userCouponRepository.countReceived(couponId, userId);
        if (mine >= limit) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "您已领取过该优惠券");
        }
        // 落库：状态 0 未使用，过期时间快照模板有效期
        UserCoupon uc = new UserCoupon();
        uc.setCouponId(couponId);
        uc.setUserId(userId);
        uc.setStatus(STATUS_UNUSED);
        uc.setExpireAt(coupon.getValidUntil());
        userCouponRepository.insert(uc);
        log.info("用户领取优惠券: userId={}, couponId={}, userCouponId={}", userId, couponId, uc.getId());
        return toVO(uc);
    }

    /** 我的优惠券（statusTab：0=未使用[含锁定] 2=已使用 3=已过期；null=全部） */
    public PageResult<UserCouponVO> listMy(Long userId, Integer statusTab, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<UserCoupon> list = userCouponRepository.selectByUser(userId, statusTab, offset, pageSize);
        long total = userCouponRepository.countByUser(userId, statusTab);
        List<UserCouponVO> vos = list.stream().map(this::toVO).toList();
        return new PageResult<>(total, vos);
    }

    /**
     * 可用券列表：结算页按"订单商品金额"筛选当前可用（未使用 + 未过期 + 门槛满足）。
     * 只返回门槛 ≤ amount 的券，前端展示可选。
     */
    public List<UserCouponVO> listAvailable(Long userId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "订单金额不合法");
        }
        return userCouponRepository.selectAvailable(userId, amount, LocalDateTime.now())
                .stream().map(this::toVO).toList();
    }

    /**
     * 计算一张券的优惠金额（下单前校验，不落库）。
     *
     * @param userId          当前用户
     * @param userCouponId    用户券 ID
     * @param originalAmount  商品原价合计（活动优惠前，门槛判断基准）
     * @param afterPromotion  活动优惠后的金额（券优惠作用基准）
     * @return 优惠金额（≥0，最低支付 0）
     */
    public BigDecimal calcDiscount(Long userId, Long userCouponId,
                                   BigDecimal originalAmount, BigDecimal afterPromotion) {
        UserCoupon uc = checkUsable(userId, userCouponId, originalAmount);
        BigDecimal base = afterPromotion == null ? BigDecimal.ZERO : afterPromotion;
        if (base.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "订单金额为 0，无需使用优惠券");
        }
        BigDecimal discount;
        if (uc.getCouponType() != null && uc.getCouponType() == 2) {
            // 折扣券：实付 = base × rate，优惠 = base × (1 - rate)
            BigDecimal rate = uc.getDiscountRate();
            discount = base.multiply(BigDecimal.ONE.subtract(rate)).setScale(2, RoundingMode.HALF_UP);
        } else {
            // 满减券：优惠 = amount（不超过 base，最低支付 0）
            discount = uc.getAmount() == null ? BigDecimal.ZERO : uc.getAmount();
        }
        if (discount.compareTo(base) > 0) {
            discount = base;
        }
        return discount.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 下单锁定券（订单落库后调用）：仅未使用(0)可锁 → 已锁定(1)并绑定订单。
     * 条件 UPDATE 保证并发下不会同一张券被两个订单锁走。
     */
    @Transactional
    public void lock(Long userId, Long userCouponId, Long orderId) {
        if (userCouponRepository.lockCoupon(userCouponId, userId, orderId) == 0) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "优惠券不可用（已使用/已过期或不属于您）");
        }
        log.info("下单锁定优惠券: userCouponId={}, orderId={}", userCouponId, orderId);
    }

    /** 支付成功核销（OrderService.markPaid 调用，幂等：只核销已锁定的券） */
    public void markUsed(Long orderId) {
        userCouponRepository.markUsedByOrder(orderId);
    }

    /** 订单取消/关闭/超时释放（幂等：只释放已锁定且绑定该订单的券） */
    public void release(Long orderId) {
        userCouponRepository.releaseByOrder(orderId);
    }

    // ==================== 管理端 ====================

    /** 管理端模板列表（分页） */
    public PageResult<CouponVO> adminList(int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<Coupon> list = couponRepository.selectPage(offset, pageSize);
        long total = couponRepository.countAll();
        List<CouponVO> vos = list.stream().map(this::toVO).toList();
        for (CouponVO vo : vos) {
            vo.setStatusText(vo.getStatus() != null && vo.getStatus() == 1 ? "上架" : "下架");
        }
        return new PageResult<>(total, vos);
    }

    /** 新增模板 */
    @Transactional
    public CouponVO create(CouponCreateDTO dto) {
        Coupon coupon = new Coupon();
        applyDto(coupon, dto);
        couponRepository.insert(coupon);
        log.info("新增优惠券模板: id={}, name={}, type={}", coupon.getId(), coupon.getName(), coupon.getType());
        return toVO(coupon);
    }

    /** 更新模板 */
    @Transactional
    public CouponVO update(Long id, CouponCreateDTO dto) {
        Coupon coupon = couponRepository.selectById(id);
        if (coupon == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "优惠券不存在");
        }
        applyDto(coupon, dto);
        couponRepository.update(coupon);
        log.info("更新优惠券模板: id={}, name={}", id, dto.getName());
        return toVO(coupon);
    }

    /** 删除模板（已领取的券记录保留，仅模板不可再领） */
    @Transactional
    public void delete(Long id) {
        couponRepository.deleteById(id);
        log.info("删除优惠券模板: id={}", id);
    }

    /**
     * 管理端定向发放：按手机号找到用户，直接发 quantity 张券（不走领取校验）。
     * 发放数量不占用用户"限领"口径（管理端发放不受 per_user_limit 限制）。
     */
    @Transactional
    public int issue(String phone, Long couponId, int quantity) {
        User user = userRepository.findByPhone(phone);
        if (user == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "该手机号用户不存在");
        }
        Coupon coupon = couponRepository.selectById(couponId);
        if (coupon == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "优惠券不存在");
        }
        if (quantity <= 0 || quantity > 100) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "发放数量需在 1~100 之间");
        }
        for (int i = 0; i < quantity; i++) {
            UserCoupon uc = new UserCoupon();
            uc.setCouponId(couponId);
            uc.setUserId(user.getId());
            uc.setStatus(STATUS_UNUSED);
            uc.setExpireAt(coupon.getValidUntil());
            userCouponRepository.insert(uc);
        }
        log.info("管理端发放优惠券: phone={}, couponId={}, quantity={}", phone, couponId, quantity);
        return quantity;
    }

    // ==================== 私有工具 ====================

    /** 校验一张券对当前用户 + 金额是否可用，返回带模板信息的券（不落库） */
    private UserCoupon checkUsable(Long userId, Long userCouponId, BigDecimal amount) {
        UserCoupon uc = userCouponRepository.selectById(userCouponId);
        if (uc == null || !uc.getUserId().equals(userId)) {
            throw new BusinessException(BusinessException.NOT_FOUND, "优惠券不存在");
        }
        if (uc.getStatus() == null || uc.getStatus() != STATUS_UNUSED) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "该优惠券不可用（已使用/已锁定）");
        }
        if (uc.getExpireAt() != null && uc.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "该优惠券已过期");
        }
        BigDecimal threshold = uc.getThreshold() == null ? BigDecimal.ZERO : uc.getThreshold();
        if (amount == null || amount.compareTo(threshold) < 0) {
            throw new BusinessException(BusinessException.PARAM_ERROR,
                    "订单金额未达到优惠券使用门槛（满 ¥" + threshold + " 可用）");
        }
        return uc;
    }

    /** DTO → 实体（新增/更新共用） */
    private void applyDto(Coupon coupon, CouponCreateDTO dto) {
        coupon.setName(dto.getName().trim());
        coupon.setType(dto.getType());
        coupon.setThreshold(dto.getThreshold());
        coupon.setAmount(dto.getType() == 1 ? dto.getAmount() : null);
        coupon.setDiscountRate(dto.getType() == 2 ? dto.getDiscountRate() : null);
        coupon.setTotalQuantity(dto.getTotalQuantity() == null ? 0 : dto.getTotalQuantity());
        coupon.setPerUserLimit(dto.getPerUserLimit() == null ? 1 : dto.getPerUserLimit());
        coupon.setStatus(dto.getStatus());
        coupon.setValidFrom(dto.getValidFrom());
        coupon.setValidUntil(dto.getValidUntil());
    }

    /** 模板实体 → VO */
    private CouponVO toVO(Coupon c) {
        CouponVO vo = new CouponVO();
        vo.setId(c.getId());
        vo.setName(c.getName());
        vo.setType(c.getType());
        vo.setTypeText(c.getType() != null && c.getType() == 2 ? "折扣券" : "满减券");
        vo.setThreshold(c.getThreshold());
        vo.setAmount(c.getAmount());
        vo.setDiscountRate(c.getDiscountRate());
        vo.setTotalQuantity(c.getTotalQuantity());
        vo.setPerUserLimit(c.getPerUserLimit());
        vo.setStatus(c.getStatus());
        vo.setValidFrom(c.getValidFrom());
        vo.setValidUntil(c.getValidUntil());
        vo.setReceivedCount(c.getReceivedCount());
        return vo;
    }

    /** 用户券实体 → VO（补中文名） */
    private UserCouponVO toVO(UserCoupon uc) {
        UserCouponVO vo = new UserCouponVO();
        vo.setId(uc.getId());
        vo.setCouponId(uc.getCouponId());
        vo.setCouponName(uc.getCouponName());
        vo.setType(uc.getCouponType());
        vo.setTypeText(uc.getCouponType() != null && uc.getCouponType() == 2 ? "折扣券" : "满减券");
        vo.setThreshold(uc.getThreshold());
        vo.setAmount(uc.getAmount());
        vo.setDiscountRate(uc.getDiscountRate());
        vo.setStatus(uc.getStatus());
        vo.setStatusText(statusText(uc.getStatus()));
        vo.setOrderId(uc.getOrderId());
        vo.setReceivedAt(uc.getReceivedAt());
        vo.setExpireAt(uc.getExpireAt());
        vo.setUsedAt(uc.getUsedAt());
        return vo;
    }

    /** 用户券状态中文名 */
    private String statusText(Integer status) {
        return switch (status == null ? -1 : status) {
            case STATUS_UNUSED -> "未使用";
            case STATUS_LOCKED -> "使用中（待付款）";
            case STATUS_USED -> "已使用";
            case STATUS_EXPIRED -> "已过期";
            default -> "未知";
        };
    }
}
