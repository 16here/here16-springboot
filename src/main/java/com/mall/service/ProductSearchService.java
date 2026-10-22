package com.mall.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import com.mall.dto.ProductQueryDTO;
import com.mall.entity.Category;
import com.mall.entity.Product;
import com.mall.repository.CategoryRepository;
import com.mall.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Elasticsearch 商品搜索服务（阶段 12：把商品关键词搜索从 MySQL LIKE 升级为 ES 全文检索）。
 *
 * <p>职责：
 * <ul>
 *   <li><b>索引管理</b>：维护商品索引 {@code mall_products}（mapping 用中文 IK 分词
 *       ik_max_word 建索引 / ik_smart 查询），应用启动时索引不存在则自动创建并全量灌入；</li>
 *   <li><b>文档同步</b>：商品新增/编辑/上下架/删除时由 {@link ProductService} 调用
 *       {@link #indexProduct}/{@link #deleteProduct} 保持索引与 MySQL 一致（双写）；</li>
 *   <li><b>关键词检索</b>：multi_match 在 名称(权重3)/描述/SKU 上做 BM25 相关性打分，
 *       支持分类筛选（含子分类）、状态过滤、价格排序与分页，返回命中的商品 ID 列表与总数；</li>
 *   <li><b>降级策略（关键设计）</b>：ES 未启动或请求异常时，{@link #search} 返回
 *       {@code null}，由 {@link ProductService#query} 降级回 MySQL LIKE——搜索功能
 *       永远可用，ES 只是性能与相关性的增强，不是可用性依赖。</li>
 * </ul>
 *
 * <p>设计取舍：ES 索引只存"检索所需的字段"（id/name/sku/description/category/price/status），
 * <b>不存冗余展示字段</b>（主图、库存、销量等）；搜索命中后仍由 MySQL 按 ID 回查
 * 组装 VO。MySQL 始终是数据权威，ES 只管"找到哪些商品"，避免双写字段膨胀与一致性问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchService {

    /** 商品索引名 */
    public static final String INDEX = "mall_products";

    /** 全量重建时每批拉取条数 */
    private static final int BATCH_SIZE = 500;

    private final ElasticsearchClient client;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    /**
     * 应用就绪后初始化：索引不存在则创建 mapping 并全量灌入。
     * 索引已存在则跳过（避免每次重启闪断）；数据增量靠 CRUD 双写保持。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initIndex() {
        try {
            boolean exists = client.indices().exists(e -> e.index(INDEX)).value();
            if (exists) {
                log.info("ES 商品索引已存在，跳过全量重建（增量由商品 CRUD 双写维护）");
                return;
            }
            client.indices().create(c -> c
                    .index(INDEX)
                    .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                    .mappings(m -> m
                            .properties("id", p -> p.long_(l -> l))
                            // 分词器：默认 standard。生产环境可装 IK 插件后改为
                            // analyzer("ik_max_word") + searchAnalyzer("ik_smart") 提升中文词义切分
                            .properties("name", p -> p.text(t -> t))
                            .properties("sku", p -> p.keyword(k -> k))
                            .properties("description", p -> p.text(t -> t))
                            .properties("categoryId", p -> p.long_(l -> l))
                            .properties("categoryName", p -> p.keyword(k -> k))
                            .properties("price", p -> p.double_(d -> d))
                            .properties("status", p -> p.integer(i -> i))
                    ));
            rebuildAll();
            log.info("ES 商品索引创建完成并全量灌入");
        } catch (Exception e) {
            log.warn("ES 初始化失败（搜索将降级为 MySQL LIKE）: {}", e.getMessage());
        }
    }

    /**
     * 索引/更新单个商品文档（upsert，幂等）。
     * 商品 CRUD 双写入口；ES 失败仅记日志，不影响 MySQL 主流程。
     */
    public void indexProduct(Product product) {
        try {
            Doc doc = toDoc(product);
            // 部分入口（新增/改状态）的 Product 实体没有 JOIN 出的分类名，这里补查一次
            if (doc.categoryName == null && product.getCategoryId() != null) {
                Category category = categoryRepository.findById(product.getCategoryId());
                doc.categoryName = category == null ? null : category.getName();
            }
            client.index(i -> i
                    .index(INDEX)
                    .id(String.valueOf(product.getId()))
                    .document(doc));
        } catch (Exception e) {
            log.warn("ES 索引商品失败（不影响主流程，可全量重建恢复）: id={}, err={}",
                    product.getId(), e.getMessage());
        }
    }

    /** 删除商品文档（商品物理删除时调用；ES 失败仅记日志） */
    public void deleteProduct(Long productId) {
        try {
            client.delete(d -> d.index(INDEX).id(String.valueOf(productId)));
        } catch (Exception e) {
            log.warn("ES 删除商品文档失败（不影响主流程）: id={}, err={}", productId, e.getMessage());
        }
    }

    /**
     * 关键词检索：返回命中的商品 ID 列表（顺序即排序结果）与总数。
     *
     * @param dto      查询条件（keyword/categoryId/sortBy/page/pageSize）
     * @param status   状态过滤；1=仅上架（用户端），null=全部（管理端）
     * @return 命中结果；ES 异常时返回 null（调用方降级 MySQL）
     */
    public SearchResult search(ProductQueryDTO dto, Integer status) {
        try {
            // 1. 关键词匹配：名称(权重 3) > 描述 > SKU，BM25 相关性打分
            BoolQuery.Builder bool = new BoolQuery.Builder();
            bool.must(m -> m.multiMatch(mm -> mm
                    .query(dto.getKeyword())
                    .fields("name^3", "description", "sku")
                    .type(TextQueryType.BestFields)));

            // 2. 状态过滤（管理端 status=null 不过滤）
            if (status != null) {
                bool.filter(f -> f.term(t -> t.field("status").value(status)));
            }

            // 3. 分类过滤：与 MySQL 语义一致（含直接子分类）
            if (dto.getCategoryId() != null) {
                List<Long> categoryIds = categoryIdsWithChildren(dto.getCategoryId());
                if (categoryIds.isEmpty()) {
                    return SearchResult.empty();
                }
                bool.filter(f -> f.terms(t -> t.field("categoryId")
                        .terms(tf -> tf.value(categoryIds.stream()
                                .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                                .toList()))));
            }

            // 4. 排序：默认按相关性 _score；价格排序显式指定（附 id 兜底保证稳定次序）
            List<SortOptions> sorts = new ArrayList<>();
            String sortBy = dto.getSortBy();
            if ("price_asc".equals(sortBy)) {
                sorts.add(SortOptions.of(s -> s.field(f -> f.field("price").order(SortOrder.Asc))));
            } else if ("price_desc".equals(sortBy)) {
                sorts.add(SortOptions.of(s -> s.field(f -> f.field("price").order(SortOrder.Desc))));
            }

            // 5. 分页
            int page = dto.getPage() == null ? 1 : Math.max(1, dto.getPage());
            int pageSize = dto.getPageSize() == null ? 12 : Math.min(100, Math.max(1, dto.getPageSize()));
            int from = (page - 1) * pageSize;

            SearchResponse<Doc> resp = client.search(s -> s
                    .index(INDEX)
                    .query(q -> q.bool(bool.build()))
                    .sort(sorts)
                    .from(from)
                    .size(pageSize), Doc.class);

            long total = resp.hits().total() == null ? 0 : resp.hits().total().value();
            List<Long> ids = resp.hits().hits().stream()
                    .map(h -> h.source() == null ? null : h.source().id)
                    .filter(id -> id != null)
                    .toList();
            return new SearchResult(ids, total);
        } catch (Exception e) {
            log.warn("ES 搜索异常，本次降级回 MySQL LIKE: keyword={}, err={}", dto.getKeyword(), e.getMessage());
            return null;
        }
    }

    /**
     * 全量重建索引：分页拉取 MySQL 全部商品，批量写入 ES（幂等，可随时手动调用恢复一致性）。
     */
    public void rebuildAll() {
        int page = 1;
        long total = 0;
        try {
            while (true) {
                ProductQueryDTO dto = new ProductQueryDTO();
                dto.setPage(page);
                dto.setPageSize(BATCH_SIZE);
                List<Product> list = productRepository.selectPage(
                        dto, null, "p.id ASC", (page - 1) * BATCH_SIZE, BATCH_SIZE);
                if (list.isEmpty()) {
                    break;
                }
                BulkRequest.Builder bulk = new BulkRequest.Builder();
                for (Product p : list) {
                    bulk.operations(op -> op.index(io -> io
                            .index(INDEX)
                            .id(String.valueOf(p.getId()))
                            .document(toDoc(p))));
                }
                client.bulk(bulk.build());
                total += list.size();
                if (list.size() < BATCH_SIZE) {
                    break;
                }
                page++;
            }
            log.info("ES 商品索引全量重建完成，共 {} 条", total);
        } catch (Exception e) {
            log.warn("ES 全量重建中断: 已处理 {} 条，err={}", total, e.getMessage());
        }
    }

    /** 分类 ID + 直接子分类 ID（与 MySQL 端"含子分类"筛选语义一致） */
    private List<Long> categoryIdsWithChildren(Long categoryId) {
        List<Long> ids = new ArrayList<>();
        ids.add(categoryId);
        for (Category child : categoryRepository.selectByParentId(categoryId)) {
            ids.add(child.getId());
        }
        return ids;
    }

    /** 实体 → ES 文档（只存检索字段） */
    private Doc toDoc(Product p) {
        Doc doc = new Doc();
        doc.id = p.getId();
        doc.name = p.getName();
        doc.sku = p.getSku();
        doc.description = p.getDescription();
        doc.categoryId = p.getCategoryId();
        doc.categoryName = p.getCategoryName();
        doc.price = p.getPrice() == null ? null : p.getPrice().doubleValue();
        doc.status = p.getStatus();
        return doc;
    }

    /** ES 文档 POJO（public 字段 + Jackson 自动映射） */
    public static class Doc {
        public Long id;
        public String name;
        public String sku;
        public String description;
        public Long categoryId;
        public String categoryName;
        public Double price;
        public Integer status;
    }

    /** 搜索结果：命中的商品 ID（顺序=排序结果）+ 总命中数 */
    public record SearchResult(List<Long> ids, long total) {
        public static SearchResult empty() {
            return new SearchResult(List.of(), 0);
        }
    }
}
