package com.mall.repository;

import com.mall.entity.Category;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 分类数据访问层（MyBatis Mapper）。
 */
@Mapper
public interface CategoryRepository {

    /** 查询全部分类（按父 ID、排序值升序，便于构建分类树） */
    @Select("SELECT * FROM categories ORDER BY parent_id, sort, id")
    List<Category> selectAll();

    /** 按父分类 ID 查询直接子分类（按排序值升序） */
    @Select("SELECT * FROM categories WHERE parent_id = #{parentId} ORDER BY sort, id")
    List<Category> selectByParentId(@Param("parentId") Long parentId);

    /** 按主键查询 */
    @Select("SELECT * FROM categories WHERE id = #{id}")
    Category findById(@Param("id") Long id);

    /**
     * 新增分类。
     *
     * @return 影响行数；自增主键回填到 category.id
     */
    @Insert("INSERT INTO categories (name, parent_id, sort) VALUES (#{name}, #{parentId}, #{sort})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Category category);

    /** 更新分类（名称/父分类/排序） */
    @Update("""
            UPDATE categories
            SET name = #{name},
                parent_id = #{parentId},
                sort = #{sort}
            WHERE id = #{id}
            """)
    int update(Category category);

    /** 删除分类 */
    @Delete("DELETE FROM categories WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /** 统计某父分类下的子分类数量（删除前校验用） */
    @Select("SELECT COUNT(*) FROM categories WHERE parent_id = #{parentId}")
    int countByParentId(@Param("parentId") Long parentId);

    /** 统计某分类下的商品数量（删除前校验用） */
    @Select("SELECT COUNT(*) FROM products WHERE category_id = #{categoryId}")
    int countProducts(@Param("categoryId") Long categoryId);
}
