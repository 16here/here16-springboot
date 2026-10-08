package com.mall.entity;

import lombok.Data;

import java.io.Serializable;

/**
 * 商品分类实体，对应表 categories。
 */
@Data
public class Category implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 分类 ID */
    private Long id;

    /** 分类名称 */
    private String name;

    /** 父分类 ID，0 表示顶级分类 */
    private Long parentId;

    /** 排序值，越小越靠前 */
    private Integer sort;
}
