package com.mall.service;

import com.mall.common.BusinessException;
import com.mall.common.PageResult;
import com.mall.dto.PromotionCreateDTO;
import com.mall.dto.PromotionVO;
import com.mall.entity.Cart;
import com.mall.entity.Promotion;
import com.mall.repository.PromotionRepository;
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
 * 营销活动服务（阶段 8）：满减 / 折扣 / 秒杀活动的计算与管理端 CRUD。
 *
 * <p>活动是"自动参与"的：下单时 OrderService 调用本服务的计算入口，
 * 无需用户领券。三类活动口径：
 * <pre>
 *   满减(1)：非秒杀商品金额 ≥ threshold → 减 amount
 *   折扣(2)：非秒杀商品金额 × (1 - discountRate) 为优惠
 *   秒杀(3)：命中规则的商品/SKU 按秒杀价成交，优惠 = Σ(原价 - 秒杀价) × 数量
 * </pre>
 * 满减/折扣同时存在多个进行中活动时取"优惠最大"的一个（不叠加，避免重复营销）；
 * 秒杀优惠与满减/折扣优惠可叠加（满减/折扣基于非秒杀商品金额）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionService {

    /** 活动类型 */
    public static final int TYPE_FULL_REDUCTION = 1; // 满减
    public static final int TYPE_DISCOUNT = 2;       // 折扣
    public static final int TYPE_FLASH_SALE = 3;     // 秒杀

    private final PromotionRepository promotionRepository;

    /** 订单活动优惠计算结果 */
    public record PromotionResult(
            BigDecimal promotionAmount,  // 活动优惠合计（秒杀 + 满减/折扣）
            BigDecimal flashDiscount,    // 秒杀优惠小计
            BigDecimal activityDiscount, // 满减/折扣优惠小计
            String activityName          // 命中的满减/折扣活动名（无则 null）
    ) {
    }

    /**
     * 计算整单活动优惠（OrderService 下单时调用）。
     * 同时完成"秒杀价替换"——返回每个购物车项的成交价。
     *
     * @param carts 勾选的购物车项（含 productId/skuId/原价/数量）
     * @return 活动优惠结果
     */
    public PromotionResult calc(List<Cart> carts) {
        BigDecimal flashDiscount = BigDecimal.ZERO;
        BigDecimal nonFlashAmount = BigDecimal.ZERO;
        List<Promotion> actives = promotionRepository.selectActive(LocalDateTime.now());
        List<Promotion.FlashSaleRule> flashRules = collectFlashRules(actives);

        for (Cart cart : carts) {
            BigDecimal original = resolvePrice(cart);
            BigDecimal flashPrice = matchFlashPrice(flashRules, cart.getProductId(),
                    cart.getSkuId() == null ? 0L : cart.getSkuId());
            BigDecimal qty = BigDecimal.valueOf(cart.getQuantity());
            if (flashPrice != null && flashPrice.compareTo(original) < 0) {
                // 命中秒杀：按秒杀价成交，优惠 = 差价 × 数量
                flashDiscount = flashDiscount.add(original.subtract(flashPrice).multiply(qty));
            } else {
                // 未命中秒杀：金额进入满减/折扣计算池
                nonFlashAmount = nonFlashAmount.add(original.multiply(qty));
            }
        }
        flashDiscount = flashDiscount.setScale(2, RoundingMode.HALF_UP);

        // 满减/折扣：取进行中活动里优惠最大的一个
        BigDecimal activityDiscount = BigDecimal.ZERO;
        String activityName = null;
        for (Promotion p : actives) {
            if (p.getType() == TYPE_FULL_REDUCTION) {
                if (nonFlashAmount.compareTo(p.getThreshold()) >= 0) {
                    BigDecimal d = p.getAmount();
                    if (d.compareTo(activityDiscount) > 0) {
                        activityDiscount = d;
                        activityName = p.getName();
                    }
                }
            } else if (p.getType() == TYPE_DISCOUNT) {
                BigDecimal discount = nonFlashAmount.multiply(
                        BigDecimal.ONE.subtract(p.getDiscountRate())).setScale(2, RoundingMode.HALF_UP);
                if (discount.compareTo(activityDiscount) > 0) {
                    activityDiscount = discount;
                    activityName = p.getName();
                }
            }
        }
        activityDiscount = activityDiscount.setScale(2, RoundingMode.HALF_UP);

        BigDecimal total = flashDiscount.add(activityDiscount).setScale(2, RoundingMode.HALF_UP);
        log.debug("活动优惠计算: 秒杀={}, 满减/折扣={}, 合计={}", flashDiscount, activityDiscount, total);
        return new PromotionResult(total, flashDiscount, activityDiscount, activityName);
    }

    /** 查询秒杀价（商品详情页可调用；null=未命中秒杀） */
    public BigDecimal getFlashSalePrice(Long productId, Long skuId) {
        List<Promotion> actives = promotionRepository.selectActive(LocalDateTime.now());
        return matchFlashPrice(collectFlashRules(actives), productId, skuId == null ? 0L : skuId);
    }

    // ==================== 管理端 ====================

    /** 活动列表（分页，含类型/状态中文与秒杀规则对象） */
    public PageResult<PromotionVO> adminList(int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<Promotion> list = promotionRepository.selectPage(offset, pageSize);
        long total = promotionRepository.countAll();
        List<PromotionVO> vos = list.stream().map(this::toVO).toList();
        return new PageResult<>(total, vos);
    }

    /** 新增活动（DTO 校验类型字段 → 秒杀规则 JSON 落库） */
    @Transactional
    public PromotionVO create(PromotionCreateDTO dto) {
        validate(dto);
        Promotion p = new Promotion();
        applyDto(p, dto);
        promotionRepository.insert(p);
        log.info("新增营销活动: id={}, name={}, type={}", p.getId(), p.getName(), p.getType());
        return toVO(p);
    }

    /** 更新活动 */
    @Transactional
    public PromotionVO update(Long id, PromotionCreateDTO dto) {
        Promotion p = promotionRepository.selectById(id);
        if (p == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "活动不存在");
        }
        validate(dto);
        applyDto(p, dto);
        promotionRepository.update(p);
        log.info("更新营销活动: id={}, name={}", id, dto.getName());
        return toVO(p);
    }

    /** 删除活动 */
    @Transactional
    public void delete(Long id) {
        promotionRepository.deleteById(id);
        log.info("删除营销活动: id={}", id);
    }

    /** 启停切换（管理端"启用/停用"按钮） */
    @Transactional
    public Integer updateStatus(Long id, Integer status) {
        Promotion p = promotionRepository.selectById(id);
        if (p == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "活动不存在");
        }
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "状态不合法");
        }
        p.setStatus(status);
        promotionRepository.update(p);
        log.info("活动启停切换: id={}, status={}", id, status);
        return status;
    }

    // ==================== 私有工具 ====================

    /** 收集所有进行中秒杀活动的规则 */
    private List<Promotion.FlashSaleRule> collectFlashRules(List<Promotion> actives) {
        List<Promotion.FlashSaleRule> rules = new ArrayList<>();
        for (Promotion p : actives) {
            if (p.getType() == TYPE_FLASH_SALE) {
                rules.addAll(parseRules(p.getRules()));
            }
        }
        return rules;
    }

    /**
     * 秒杀规则匹配：skuId 优先精确匹配；规则 skuId=0 表示整个商品都参与秒杀。
     * 返回秒杀价（BigDecimal），未命中返回 null。
     */
    private BigDecimal matchFlashPrice(List<Promotion.FlashSaleRule> rules, Long productId, Long skuId) {
        if (rules.isEmpty()) {
            return null;
        }
        BigDecimal fallback = null; // 商品级规则兜底（skuId=0）
        for (Promotion.FlashSaleRule rule : rules) {
            if (!rule.getProductId().equals(productId)) {
                continue;
            }
            Long ruleSku = rule.getSkuId() == null ? 0L : rule.getSkuId();
            if (ruleSku.equals(skuId) && skuId > 0) {
                return rule.getPrice(); // SKU 精确命中优先
            }
            if (ruleSku == 0L) {
                fallback = rule.getPrice(); // 商品级命中（无规格商品或整商品参与）
            }
        }
        return fallback;
    }

    /** DTO 类型联动校验（同 CouponCreateDTO 思路） */
    private void validate(PromotionCreateDTO dto) {
        if (dto.getType() == null) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "请选择活动类型");
        }
        if (dto.getStartTime() == null || dto.getEndTime() == null
                || !dto.getEndTime().isAfter(dto.getStartTime())) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "活动结束时间必须晚于开始时间");
        }
        switch (dto.getType()) {
            case TYPE_FULL_REDUCTION -> {
                if (dto.getThreshold() == null || dto.getAmount() == null) {
                    throw new BusinessException(BusinessException.PARAM_ERROR, "满减活动必须填写门槛与减免金额");
                }
            }
            case TYPE_DISCOUNT -> {
                if (dto.getDiscountRate() == null
                        || dto.getDiscountRate().compareTo(BigDecimal.ZERO) <= 0
                        || dto.getDiscountRate().compareTo(BigDecimal.ONE) >= 0) {
                    throw new BusinessException(BusinessException.PARAM_ERROR, "折扣率需在 0~1 之间（如 0.85=8.5 折）");
                }
            }
            case TYPE_FLASH_SALE -> {
                if (dto.getRules() == null || dto.getRules().isEmpty()) {
                    throw new BusinessException(BusinessException.PARAM_ERROR, "秒杀活动必须填写秒杀商品规则");
                }
                for (Promotion.FlashSaleRule r : dto.getRules()) {
                    if (r.getProductId() == null || r.getPrice() == null || r.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
                        throw new BusinessException(BusinessException.PARAM_ERROR, "秒杀规则必须包含商品与大于 0 的秒杀价");
                    }
                }
            }
            default -> throw new BusinessException(BusinessException.PARAM_ERROR, "活动类型不合法");
        }
    }

    /** DTO → 实体（秒杀规则 List → JSON 字符串） */
    private void applyDto(Promotion p, PromotionCreateDTO dto) {
        p.setName(dto.getName().trim());
        p.setType(dto.getType());
        p.setThreshold(dto.getType() == TYPE_FULL_REDUCTION ? dto.getThreshold() : null);
        p.setAmount(dto.getType() == TYPE_FULL_REDUCTION ? dto.getAmount() : null);
        p.setDiscountRate(dto.getType() == TYPE_DISCOUNT ? dto.getDiscountRate() : null);
        p.setRules(dto.getType() == TYPE_FLASH_SALE ? formatRules(dto.getRules()) : null);
        p.setStartTime(dto.getStartTime());
        p.setEndTime(dto.getEndTime());
        p.setStatus(dto.getStatus());
    }

    /** 秒杀规则对象列表 → JSON 字符串（轻量手写，格式固定，不依赖外部 JSON 库） */
    public String formatRules(List<Promotion.FlashSaleRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rules.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            Promotion.FlashSaleRule r = rules.get(i);
            sb.append("{\"productId\":").append(r.getProductId())
                    .append(",\"skuId\":").append(r.getSkuId() == null ? 0 : r.getSkuId())
                    .append(",\"price\":").append(r.getPrice().toPlainString())
                    .append("}");
        }
        return sb.append("]").toString();
    }

    /** JSON 字符串 → 秒杀规则对象列表（容忍格式异常：解析失败跳过该条） */
    public List<Promotion.FlashSaleRule> parseRules(String json) {
        List<Promotion.FlashSaleRule> rules = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return rules;
        }
        String body = json.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        for (String part : body.split("\\},\\s*\\{")) {
            Promotion.FlashSaleRule rule = parseOneRule(part);
            if (rule != null) {
                rules.add(rule);
            }
        }
        return rules;
    }

    /** 解析单条规则对象字符串（{...} 或片段），格式：{"productId":1,"skuId":1,"price":99} */
    private Promotion.FlashSaleRule parseOneRule(String part) {
        try {
            String body = part.trim();
            if (body.startsWith("{")) {
                body = body.substring(1);
            }
            if (body.endsWith("}")) {
                body = body.substring(0, body.length() - 1);
            }
            Long productId = null;
            Long skuId = 0L;
            BigDecimal price = null;
            for (String kv : body.split(",")) {
                String[] pair = kv.split(":", 2);
                if (pair.length != 2) {
                    continue;
                }
                String key = pair[0].trim().replace("\"", "");
                String value = pair[1].trim().replace("\"", "");
                switch (key) {
                    case "productId" -> productId = Long.parseLong(value);
                    case "skuId" -> skuId = Long.parseLong(value);
                    case "price" -> price = new BigDecimal(value);
                    default -> { /* 忽略未知字段 */ }
                }
            }
            if (productId == null || price == null) {
                return null;
            }
            return new Promotion.FlashSaleRule(productId, skuId, price);
        } catch (NumberFormatException e) {
            log.warn("秒杀规则解析失败，已跳过: {}", part);
            return null;
        }
    }

    /** 实体 → VO（类型/状态中文 + 规则对象） */
    private PromotionVO toVO(Promotion p) {
        PromotionVO vo = new PromotionVO();
        vo.setId(p.getId());
        vo.setName(p.getName());
        vo.setType(p.getType());
        vo.setTypeText(p.getType() != null ? switch (p.getType()) {
            case TYPE_FULL_REDUCTION -> "满减";
            case TYPE_DISCOUNT -> "折扣";
            case TYPE_FLASH_SALE -> "秒杀";
            default -> "未知";
        } : "未知");
        vo.setThreshold(p.getThreshold());
        vo.setAmount(p.getAmount());
        vo.setDiscountRate(p.getDiscountRate());
        vo.setStartTime(p.getStartTime());
        vo.setEndTime(p.getEndTime());
        vo.setStatus(p.getStatus());
        vo.setStatusText(p.getStatus() != null && p.getStatus() == 1 ? "启用" : "停用");
        vo.setRuleItems(parseRules(p.getRules()));
        vo.setCreatedAt(p.getCreatedAt());
        return vo;
    }

    /** 单价解析：有规格用规格价，否则用商品价（与 OrderService 一致） */
    private BigDecimal resolvePrice(Cart cart) {
        if (cart.getSkuId() != null && cart.getSkuId() > 0 && cart.getSkuPrice() != null) {
            return cart.getSkuPrice();
        }
        return cart.getProductPrice();
    }
}
