package com.mall.service;

import com.mall.common.BusinessException;
import com.mall.dto.CategoryTreeVO;
import com.mall.entity.Category;
import com.mall.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类服务：分类树构建与管理端增删改。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    /** 顶级分类的 parent_id 约定值 */
    private static final long ROOT_PARENT_ID = 0L;

    private final CategoryRepository categoryRepository;

    /**
     * 构建完整分类树（顶级分类为根，子分类递归挂载，按 sort 排序）。
     *
     * @return 顶级分类节点列表
     */
    public List<CategoryTreeVO> tree() {
        List<Category> all = categoryRepository.selectAll();
        if (all.isEmpty()) {
            return new ArrayList<>();
        }

        // 1. 先按 id 建立"实体 -> 树节点"映射，并给每个节点初始化 children
        Map<Long, CategoryTreeVO> nodeMap = new HashMap<>();
        for (Category category : all) {
            CategoryTreeVO node = new CategoryTreeVO();
            node.setId(category.getId());
            node.setName(category.getName());
            node.setParentId(category.getParentId());
            node.setSort(category.getSort());
            node.setChildren(new ArrayList<>());
            nodeMap.put(category.getId(), node);
        }

        // 2. 把每个节点挂到其父节点下；找不到父节点（或 parent_id=0）即视为顶级
        List<CategoryTreeVO> roots = new ArrayList<>();
        for (Category category : all) {
            CategoryTreeVO node = nodeMap.get(category.getId());
            if (category.getParentId() != null && category.getParentId() != ROOT_PARENT_ID
                    && nodeMap.containsKey(category.getParentId())) {
                nodeMap.get(category.getParentId()).getChildren().add(node);
            } else {
                roots.add(node);
            }
        }

        // 3. 逐层按 sort（其次 id）排序，保证展示顺序稳定
        sortRecursively(roots);
        return roots;
    }

    /** 递归排序：先排当前层，再排每个节点的子层 */
    private void sortRecursively(List<CategoryTreeVO> nodes) {
        Comparator<CategoryTreeVO> comparator =
                Comparator.comparing(CategoryTreeVO::getSort, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(CategoryTreeVO::getId);
        nodes.sort(comparator);
        for (CategoryTreeVO node : nodes) {
            sortRecursively(node.getChildren());
        }
    }

    /**
     * 平铺分类列表（管理端表格用，按 sort 排序）。
     */
    public List<Category> flatList() {
        return categoryRepository.selectAll();
    }

    /**
     * 新增分类（管理端）。
     *
     * @param category 分类信息（name 必填，parentId/sort 可空则给默认值）
     * @return 新增后的分类
     */
    @Transactional
    public Category create(Category category) {
        if (!StringUtils.hasText(category.getName())) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "分类名称不能为空");
        }
        category.setParentId(category.getParentId() == null ? ROOT_PARENT_ID : category.getParentId());
        category.setSort(category.getSort() == null ? 0 : category.getSort());

        // 父分类必须存在（顶级除外）
        if (category.getParentId() != ROOT_PARENT_ID
                && categoryRepository.findById(category.getParentId()) == null) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "父分类不存在");
        }

        categoryRepository.insert(category);
        log.info("新增分类: id={}, name={}", category.getId(), category.getName());
        return category;
    }

    /**
     * 更新分类（管理端）。
     */
    @Transactional
    public Category update(Long id, Category category) {
        Category exist = categoryRepository.findById(id);
        if (exist == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "分类不存在");
        }
        if (!StringUtils.hasText(category.getName())) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "分类名称不能为空");
        }
        // 不允许把自己挂到自己下面，避免形成环
        if (category.getParentId() != null
                && category.getParentId().equals(id)) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "父分类不能是自身");
        }
        // 顶级时 parentId 归一为 0
        category.setParentId(category.getParentId() == null ? ROOT_PARENT_ID : category.getParentId());
        category.setSort(category.getSort() == null ? 0 : category.getSort());
        category.setId(id);

        categoryRepository.update(category);
        log.info("更新分类: id={}", id);
        return categoryRepository.findById(id);
    }

    /**
     * 删除分类（管理端）。
     *
     * <p>安全校验：存在子分类或挂有商品时拒绝删除，避免产生孤儿数据。
     */
    @Transactional
    public void delete(Long id) {
        if (categoryRepository.findById(id) == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "分类不存在");
        }
        if (categoryRepository.countByParentId(id) > 0) {
            throw new BusinessException(3001, "请先删除或移动该分类下的子分类");
        }
        if (categoryRepository.countProducts(id) > 0) {
            throw new BusinessException(3002, "该分类下仍有商品，请先迁移或删除商品");
        }
        categoryRepository.deleteById(id);
        log.info("删除分类: id={}", id);
    }
}
