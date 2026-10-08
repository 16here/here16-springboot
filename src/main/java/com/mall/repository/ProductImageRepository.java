package com.mall.repository;

import com.mall.entity.ProductImage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商品图片数据访问层。
 */
@Mapper
public interface ProductImageRepository {

    /** 按商品查询图片（按排序值升序） */
    @Select("SELECT * FROM product_images WHERE product_id = #{productId} ORDER BY sort, id")
    List<ProductImage> selectByProductId(@Param("productId") Long productId);

    /** 批量插入图片 */
    @Insert("""
            <script>
            INSERT INTO product_images (product_id, image_url, sort) VALUES
            <foreach collection="list" item="img" separator=",">
              (#{img.productId}, #{img.imageUrl}, #{img.sort})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("list") List<ProductImage> images);

    /** 删除某商品的全部图片（编辑/删除商品时先清后插） */
    @Delete("DELETE FROM product_images WHERE product_id = #{productId}")
    int deleteByProductId(@Param("productId") Long productId);
}
