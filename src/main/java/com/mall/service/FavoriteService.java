package com.mall.service;

import com.mall.common.BusinessException;
import com.mall.common.ShardingRouter;
import com.mall.config.ShardingTableManager;
import com.mall.dto.ProductListVO;
import com.mall.entity.Product;
import com.mall.entity.ProductImage;
import com.mall.entity.UserBehavior;
import com.mall.repository.ProductImageRepository;
import com.mall.repository.ProductRepository;
import com.mall.repository.UserBehaviorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 收藏服务（阶段 11 需求 3）。
 *
 * <p>收藏的实现思路：收藏本质就是"用户行为表里的一条 FAVORITE 记录"——
 * 与阶段 10 推荐系统共用 user_behaviors 表：
 * <ul>
 *   <li>收藏 = 插入 FAVORITE 行为（权重 2，会直接影响热度推荐与个性化推荐）；</li>
 *   <li>取消收藏 = 删除这条 FAVORITE 记录；</li>
 *   <li>收藏夹 = 按用户查全部 FAVORITE 记录对应的商品。</li>
 * </ul>
 *
 * <p>与普通埋点的区别：收藏/取消收藏是用户的**业务主操作**（按钮必须有明确结果），
 * 因此这里直接调用 repository 并让异常向上抛（由全局异常处理器转成错误响应），
 * 而不是像浏览埋点那样 try/catch 静默吞掉。
 */
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final UserBehaviorRepository behaviorRepository;
    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ShardingTableManager tableManager;

    /** 收藏行为类型（与 user_behaviors 表注释、推荐权重口径一致） */
    private static final String TYPE_FAVORITE = UserBehaviorRepository.TYPE_FAVORITE;

    /**
     * 收藏商品（幂等）。
     *
     * <p>已收藏时直接返回成功（重复点击收藏不会产生重复记录）；
     * 商品不存在时抛 404 业务异常。
     */
    public void add(Long userId, Long productId) {
        if (productRepository.findById(productId) == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "商品不存在");
        }
        if (isFavorite(userId, productId)) {
            return; // 幂等：已收藏，忽略本次点击
        }
        UserBehavior behavior = new UserBehavior();
        behavior.setUserId(userId);
        behavior.setProductId(productId);
        behavior.setBehaviorType(TYPE_FAVORITE);
        // Phase 3 分表：user_behaviors 按月分表，写入前确保当月分表存在并显式传表名
        String suffix = ShardingRouter.currentMonthSuffix();
        tableManager.ensureMonthTable(ShardingRouter.T_USER_BEHAVIORS, suffix.substring(1));
        behaviorRepository.insert(ShardingRouter.tableName(ShardingRouter.T_USER_BEHAVIORS, suffix), behavior);
    }

    /**
     * 取消收藏（幂等）。
     *
     * <p>未收藏时删除 0 行，同样视为成功（前端心形按钮状态不会错乱）。
     * Phase 3：user_behaviors 按月分表，删除需显式传当月表名。
     */
    public void remove(Long userId, Long productId) {
        behaviorRepository.deleteByUserAndProductAndType(
                ShardingRouter.tableName(ShardingRouter.T_USER_BEHAVIORS, ShardingRouter.currentMonthSuffix()),
                userId, productId, TYPE_FAVORITE);
    }

    /**
     * 收藏夹列表（最新收藏在前）。
     *
     * <p>复用商品列表 VO（主图取第一张图片），
     * 前端收藏夹页与商品列表页共用同一套卡片组件。
     */
    public List<ProductListVO> list(Long userId) {
        List<Long> ids = behaviorRepository.selectProductIdsByUserAndType(userId, TYPE_FAVORITE);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Product> products = productRepository.selectByIds(ids);
        List<ProductListVO> list = new ArrayList<>(products.size());
        for (Product p : products) {
            ProductListVO vo = new ProductListVO();
            vo.setId(p.getId());
            vo.setSku(p.getSku());
            vo.setName(p.getName());
            vo.setPrice(p.getPrice());
            // 展示可卖库存（与列表页口径一致：stock - locked_stock）
            vo.setStock(p.getStock() - (p.getLockedStock() == null ? 0 : p.getLockedStock()));
            vo.setStatus(p.getStatus());
            vo.setCategoryId(p.getCategoryId());
            vo.setCategoryName(p.getCategoryName());
            // 主图：第一张图片，无图时为 null（前端有兜底占位）
            List<ProductImage> images = productImageRepository.selectByProductId(p.getId());
            vo.setCover(images.isEmpty() ? null : images.get(0).getImageUrl());
            list.add(vo);
        }
        return list;
    }

    /** 是否已收藏（商品详情页收藏按钮初始状态） */
    public boolean isFavorite(Long userId, Long productId) {
        return behaviorRepository.countByUserAndProductAndType(userId, productId, TYPE_FAVORITE) > 0;
    }
}
