package com.mall.service;

import com.mall.annotation.DS;
import com.mall.common.BusinessException;
import com.mall.dto.CartAddDTO;
import com.mall.dto.CartItemVO;
import com.mall.dto.CartVO;
import com.mall.entity.Cart;
import com.mall.entity.Product;
import com.mall.entity.ProductSku;
import com.mall.repository.CartRepository;
import com.mall.repository.ProductRepository;
import com.mall.repository.ProductSkuRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 购物车服务：列表、添加、改数量、删除、清空、登录合并、小计计算。
 *
 * <p>设计要点：
 * <ul>
 *   <li>所有按 id 的操作都带 userId 校验（Repository 层 WHERE user_id），杜绝越权；</li>
 *   <li>skuId=0 表示未选规格；单价与库存按"有规格用规格、无规格用商品"取值；</li>
 *   <li>添加时同一 (userId, skuId) 只存一行，重复添加做数量累加（上限 99）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    /** 单个商品可购买数量上限 */
    private static final int MAX_QUANTITY = 99;

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final ProductSkuRepository productSkuRepository;
    private final RecommendService recommendService;

    /**
     * 查询购物车：明细 + 总件数 + 总金额。
     *
     * <p>强制主库读（Phase 3 读写分离后）：加购/改数量写主库后，
     * 前端立刻刷新购物车，若走从库会因复制延迟看到"空车/旧数量"。
     * 购物车是强一致数据且量小，全程主库读性价比最高。
     */
    @DS("master")
    public CartVO list(Long userId) {
        List<Cart> carts = cartRepository.selectByUserId(userId);
        List<CartItemVO> items = new ArrayList<>();
        int totalCount = 0;
        BigDecimal totalPrice = BigDecimal.ZERO;

        for (Cart cart : carts) {
            CartItemVO vo = new CartItemVO();
            vo.setCartId(cart.getId());
            vo.setProductId(cart.getProductId());
            vo.setSkuId(cart.getSkuId());
            vo.setQuantity(cart.getQuantity());
            vo.setProductName(cart.getProductName());
            vo.setProductImage(cart.getProductImage());
            vo.setSkuName(cart.getSkuName());
            // 单价：有规格用规格价，否则用商品价
            BigDecimal price = resolvePrice(cart);
            vo.setPrice(price);
            vo.setStock(resolveStock(cart));
            items.add(vo);

            totalCount += cart.getQuantity();
            totalPrice = totalPrice.add(price.multiply(BigDecimal.valueOf(cart.getQuantity())));
        }
        return new CartVO(items, totalCount, totalPrice);
    }

    /**
     * 添加购物车（前端"加入购物车"调用）。
     * 同一 (用户, 规格) 已存在时数量累加。
     */
    @Transactional
    public CartVO add(Long userId, CartAddDTO dto) {
        doAdd(userId, dto);
        return list(userId);
    }

    /**
     * 登录合并：把游客期间本地保存的购物车逐项并入服务器购物车。
     */
    @Transactional
    public CartVO merge(Long userId, List<CartAddDTO> items) {
        if (items != null) {
            for (CartAddDTO item : items) {
                doAdd(userId, item);
            }
        }
        return list(userId);
    }

    /** 更新数量（校验归属，越权/不存在返回业务 404） */
    @Transactional
    public CartVO updateQuantity(Long userId, Long cartId, Integer quantity) {
        if (cartRepository.updateQuantity(cartId, userId, quantity) == 0) {
            throw new BusinessException(BusinessException.NOT_FOUND, "购物车项不存在");
        }
        return list(userId);
    }

    /** 删除单项（校验归属） */
    @Transactional
    public CartVO remove(Long userId, Long cartId) {
        if (cartRepository.deleteByIdAndUserId(cartId, userId) == 0) {
            throw new BusinessException(BusinessException.NOT_FOUND, "购物车项不存在");
        }
        return list(userId);
    }

    /** 清空购物车 */
    @Transactional
    public void clear(Long userId) {
        cartRepository.deleteByUserId(userId);
        log.info("清空购物车: userId={}", userId);
    }

    /**
     * 添加的核心逻辑（add 与 merge 共用）。
     * 校验：商品存在且上架；规格存在且属于该商品；数量合并后不超上限。
     */
    private void doAdd(Long userId, CartAddDTO dto) {
        // 1. 商品校验
        Product product = productRepository.findById(dto.getProductId());
        if (product == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "商品不存在");
        }
        if (product.getStatus() == null || product.getStatus() != 1) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "商品已下架，无法加入购物车");
        }

        // 2. 规格校验（skuId>0 才需要）
        Long skuId = dto.getSkuId() == null ? 0L : dto.getSkuId();
        if (skuId > 0) {
            ProductSku sku = productSkuRepository.findById(skuId);
            if (sku == null || !sku.getProductId().equals(product.getId())) {
                throw new BusinessException(BusinessException.PARAM_ERROR, "商品规格不存在");
            }
        }

        // 3. 数量校验与合并
        int quantity = dto.getQuantity() == null ? 1 : dto.getQuantity();
        Cart exist = cartRepository.findByUserIdAndSkuId(userId, skuId);
        if (exist != null) {
            int merged = exist.getQuantity() + quantity;
            if (merged > MAX_QUANTITY) {
                throw new BusinessException(BusinessException.PARAM_ERROR, "单个商品最多购买 " + MAX_QUANTITY + " 件");
            }
            cartRepository.updateQuantity(exist.getId(), userId, merged);
        } else {
            Cart cart = new Cart();
            cart.setUserId(userId);
            cart.setProductId(product.getId());
            cart.setSkuId(skuId);
            cart.setQuantity(quantity);
            cartRepository.insert(cart);
        }
        // 阶段 10 埋点：加购行为（CART，权重 3）——推荐系统"热度 + 协同过滤"的燃料；
        // 合并数量时也算一次加购（用户明确表达了想买），记录失败不影响加购主流程
        recommendService.record(userId, product.getId(), skuId,
                com.mall.repository.UserBehaviorRepository.TYPE_CART);
    }

    /** 单价解析：有规格用规格价，否则用商品价 */
    private BigDecimal resolvePrice(Cart cart) {
        if (cart.getSkuId() != null && cart.getSkuId() > 0 && cart.getSkuPrice() != null) {
            return cart.getSkuPrice();
        }
        return cart.getProductPrice();
    }

    /** 库存解析：有规格用规格库存，否则用商品库存 */
    private Integer resolveStock(Cart cart) {
        if (cart.getSkuId() != null && cart.getSkuId() > 0 && cart.getSkuStock() != null) {
            return cart.getSkuStock();
        }
        return cart.getProductStock();
    }
}
