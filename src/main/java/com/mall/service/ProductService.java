package com.mall.service;

import com.mall.common.BusinessException;
import com.mall.common.PageResult;
import com.mall.dto.ProductCreateDTO;
import com.mall.dto.ProductDetailVO;
import com.mall.dto.ProductListVO;
import com.mall.dto.ProductQueryDTO;
import com.mall.dto.ProductUpdateDTO;
import com.mall.dto.SkuItemDTO;
import com.mall.entity.Category;
import com.mall.entity.Product;
import com.mall.entity.ProductImage;
import com.mall.entity.ProductSku;
import com.mall.repository.CategoryRepository;
import com.mall.repository.ProductImageRepository;
import com.mall.repository.ProductRepository;
import com.mall.repository.ProductSkuRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 商品服务：用户端列表/详情/搜索/排序 + 管理端增删改查与上下架。
 *
 * <p>排序字段白名单：用户传入的 sortBy 在此映射为 SQL 片段，杜绝 SQL 注入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    /** 排序白名单：sortBy -> 对应 ORDER BY 片段 */
    private static final Map<String, String> SORT_WHITELIST = Map.of(
            "default", "p.id DESC",                     // 最新优先
            "price_asc", "p.price ASC, p.id DESC",      // 价格升序
            "price_desc", "p.price DESC, p.id DESC"     // 价格降序
    );

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductSkuRepository productSkuRepository;
    private final CategoryRepository categoryRepository;
    private final RedisStockService redisStockService;
    private final RecommendService recommendService;

    /**
     * 记录浏览行为（阶段 10 埋点：商品详情被访问时调用）。
     *
     * <p>浏览行为是推荐系统的"燃料"之一（热度统计 VIEW 权重 1、
     * 协同过滤"看过 A 的人还看了 B"都依赖它）。userId 可为 null（游客浏览），
     * 游客行为只参与热度统计；行为记录失败不影响商品浏览主流程。
     */
    public void recordView(Long productId, Long userId) {
        recommendService.record(userId, productId, null,
                com.mall.repository.UserBehaviorRepository.TYPE_VIEW);
    }

    /**
     * SKU 实时可卖库存（阶段 6：优先读 Redis，无 key 回源数据库并预热）。
     * 前端"实时库存"展示与加购前校验用。
     */
    public int getSkuStock(Long skuId) {
        ProductSku sku = productSkuRepository.findById(skuId);
        if (sku == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "规格不存在");
        }
        int dbAvailable = sku.getStock()
                - (sku.getLockedStock() == null ? 0 : sku.getLockedStock());
        return redisStockService.getAvailable(RedisStockService.skuKey(skuId), dbAvailable);
    }

    /**
     * 商品级实时可卖库存（无规格商品，阶段 6）。
     */
    public int getProductStock(Long productId) {
        Product product = productRepository.findById(productId);
        if (product == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "商品不存在");
        }
        int dbAvailable = product.getStock()
                - (product.getLockedStock() == null ? 0 : product.getLockedStock());
        return redisStockService.getAvailable(RedisStockService.productKey(productId), dbAvailable);
    }

    /**
     * 用户端商品分页：仅返回上架商品。
     */
    public PageResult<ProductListVO> page(ProductQueryDTO dto) {
        return query(dto, 1);
    }

    /**
     * 管理端商品分页：返回全部状态商品。
     */
    public PageResult<ProductListVO> adminPage(ProductQueryDTO dto) {
        return query(dto, null);
    }

    /**
     * 分页查询并转换为列表 VO。
     *
     * @param dto    查询条件
     * @param status 状态过滤；1=仅上架（用户端），null=全部（管理端）
     */
    private PageResult<ProductListVO> query(ProductQueryDTO dto, Integer status) {
        // 页码与每页数量防御（DTO 已有 @Valid，这里再兜底一次）
        int page = dto.getPage() == null ? 1 : Math.max(1, dto.getPage());
        int pageSize = dto.getPageSize() == null ? 12 : Math.min(100, Math.max(1, dto.getPageSize()));
        int offset = (page - 1) * pageSize;

        // 排序白名单映射：未知值回退默认排序。
        // 注意：Map.of 生成的不可变 Map 不接受 null key，getOrDefault(null) 会抛 NPE，
        // 因此必须先判空再查询（前端不传 sortBy 时该字段为 null，属正常入参）。
        String sortBy = dto.getSortBy();
        String orderBy = sortBy == null
                ? SORT_WHITELIST.get("default")
                : SORT_WHITELIST.getOrDefault(sortBy, SORT_WHITELIST.get("default"));

        long total = productRepository.count(dto, status);
        List<Product> products = productRepository.selectPage(dto, status, orderBy, offset, pageSize);

        // 列表 VO 组装：主图取该商品第一张图片
        List<ProductListVO> list = new ArrayList<>();
        for (Product p : products) {
            ProductListVO vo = new ProductListVO();
            vo.setId(p.getId());
            vo.setSku(p.getSku());
            vo.setName(p.getName());
            vo.setPrice(p.getPrice());
            // 阶段 6：对外展示"可卖库存"（stock - locked_stock），与下单口径一致
            vo.setStock(p.getStock() - (p.getLockedStock() == null ? 0 : p.getLockedStock()));
            vo.setStatus(p.getStatus());
            vo.setCategoryId(p.getCategoryId());
            vo.setCategoryName(p.getCategoryName());
            // 每页最多 12 个商品，逐条查主图成本可控；数据量大时可改为批量 IN 查询
            List<ProductImage> images = productImageRepository.selectByProductId(p.getId());
            vo.setCover(images.isEmpty() ? null : images.get(0).getImageUrl());
            list.add(vo);
        }
        return new PageResult<>(total, list);
    }

    /**
     * 商品详情（图片 + 规格）。
     *
     * @param id 商品 ID
     */
    public ProductDetailVO detail(Long id) {
        Product product = requireProduct(id);
        return buildDetailVO(product);
    }

    /**
     * 新增商品（管理端）：基本信息 + 图片 + 规格，同一事务内落库。
     */
    @Transactional
    public Long create(ProductCreateDTO dto) {
        // SKU 编码唯一性校验
        if (productRepository.findBySku(dto.getSku()) != null) {
            throw new BusinessException(3101, "SKU 编码已存在");
        }
        requireCategory(dto.getCategoryId());

        Product product = new Product();
        product.setSku(dto.getSku());
        product.setName(dto.getName());
        product.setDescription(dto.getDescription());
        product.setPrice(dto.getPrice());
        product.setStock(dto.getStock());
        product.setCategoryId(dto.getCategoryId());
        product.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());

        productRepository.insert(product); // 主键回填
        saveImagesAndSkus(product.getId(), dto.getImages(), dto.getSkus());
        log.info("新增商品: id={}, sku={}", product.getId(), product.getSku());
        return product.getId();
    }

    /**
     * 更新商品（管理端，全量替换图片与规格）。
     */
    @Transactional
    public void update(Long id, ProductUpdateDTO dto) {
        requireProduct(id);
        // SKU 唯一性校验（排除自身）
        Product exist = productRepository.findBySku(dto.getSku());
        if (exist != null && !exist.getId().equals(id)) {
            throw new BusinessException(3101, "SKU 编码已存在");
        }
        requireCategory(dto.getCategoryId());

        Product product = new Product();
        product.setId(id);
        product.setSku(dto.getSku());
        product.setName(dto.getName());
        product.setDescription(dto.getDescription());
        product.setPrice(dto.getPrice());
        product.setStock(dto.getStock());
        product.setCategoryId(dto.getCategoryId());
        product.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());

        productRepository.update(product);
        // 图片/规格全量替换：先清后插
        productImageRepository.deleteByProductId(id);
        productSkuRepository.deleteByProductId(id);
        saveImagesAndSkus(id, dto.getImages(), dto.getSkus());
        log.info("更新商品: id={}", id);
    }

    /**
     * 删除商品（管理端，物理删除并连带图片/规格）。
     */
    @Transactional
    public void delete(Long id) {
        requireProduct(id);
        productRepository.deleteById(id);
        productImageRepository.deleteByProductId(id);
        productSkuRepository.deleteByProductId(id);
        log.info("删除商品: id={}", id);
    }

    /**
     * 上下架（管理端）。
     *
     * @param id     商品 ID
     * @param status 1=上架，0=下架
     */
    @Transactional
    public void updateStatus(Long id, Integer status) {
        requireProduct(id);
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "状态值不合法（0=下架，1=上架）");
        }
        productRepository.updateStatus(id, status);
        log.info("商品上下架: id={}, status={}", id, status);
    }

    /**
     * 保存商品图片与规格（新增/更新共用）。
     */
    private void saveImagesAndSkus(Long productId, List<String> images, List<SkuItemDTO> skus) {
        if (images != null) {
            List<ProductImage> imageList = new ArrayList<>();
            for (int i = 0; i < images.size(); i++) {
                if (!StringUtils.hasText(images.get(i))) {
                    continue; // 忽略空图片地址
                }
                ProductImage img = new ProductImage();
                img.setProductId(productId);
                img.setImageUrl(images.get(i).trim());
                img.setSort(i);
                imageList.add(img);
            }
            if (!imageList.isEmpty()) {
                productImageRepository.insertBatch(imageList);
            }
        }

        if (skus != null && !skus.isEmpty()) {
            List<ProductSku> skuList = new ArrayList<>();
            for (SkuItemDTO item : skus) {
                ProductSku sku = new ProductSku();
                sku.setProductId(productId);
                sku.setName(item.getName());
                sku.setPrice(item.getPrice());
                sku.setStock(item.getStock());
                sku.setImage(item.getImage());
                skuList.add(sku);
            }
            productSkuRepository.insertBatch(skuList);
        }
    }

    /**
     * 组装详情 VO：商品基本信息 + 图片 + 规格。
     */
    private ProductDetailVO buildDetailVO(Product product) {
        ProductDetailVO vo = new ProductDetailVO();
        vo.setId(product.getId());
        vo.setSku(product.getSku());
        vo.setName(product.getName());
        vo.setDescription(product.getDescription());
        vo.setPrice(product.getPrice());
        // 阶段 6：对外展示"可卖库存"（stock - locked_stock）
        vo.setStock(product.getStock() - (product.getLockedStock() == null ? 0 : product.getLockedStock()));
        vo.setStatus(product.getStatus());
        vo.setCategoryId(product.getCategoryId());

        Category category = categoryRepository.findById(product.getCategoryId());
        vo.setCategoryName(category == null ? null : category.getName());

        // 图片：仅取地址列表
        List<ProductImage> images = productImageRepository.selectByProductId(product.getId());
        vo.setImages(images.stream().map(ProductImage::getImageUrl).toList());

        // 规格
        List<ProductSku> skus = productSkuRepository.selectByProductId(product.getId());
        List<ProductDetailVO.SkuItemVO> skuVOs = new ArrayList<>();
        for (ProductSku sku : skus) {
            ProductDetailVO.SkuItemVO item = new ProductDetailVO.SkuItemVO();
            item.setId(sku.getId());
            item.setName(sku.getName());
            item.setPrice(sku.getPrice());
            // 阶段 6：SKU 可卖库存（stock - locked_stock），前端据此禁用无货规格
            item.setStock(sku.getStock() - (sku.getLockedStock() == null ? 0 : sku.getLockedStock()));
            item.setImage(sku.getImage());
            skuVOs.add(item);
        }
        vo.setSkus(skuVOs);
        return vo;
    }

    /** 加载商品并校验存在 */
    private Product requireProduct(Long id) {
        Product product = productRepository.findById(id);
        if (product == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "商品不存在");
        }
        return product;
    }

    /** 校验分类存在 */
    private void requireCategory(Long categoryId) {
        if (categoryRepository.findById(categoryId) == null) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "所选分类不存在");
        }
    }
}
