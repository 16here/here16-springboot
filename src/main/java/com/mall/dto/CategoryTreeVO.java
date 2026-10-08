package com.mall.dto;

import lombok.Data;

import java.util.List;

/**
 * 分类树节点视图对象（递归构建分类树）。
 */
@Data
public class CategoryTreeVO {

    /** 分类 ID */
    private Long id;

    /** 分类名称 */
    private String name;

    /** 父分类 ID，0=顶级 */
    private Long parentId;

    /** 排序值 */
    private Integer sort;

    /** 子分类列表 */
    private List<CategoryTreeVO> children;
}
