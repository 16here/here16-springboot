package com.mall.repository;

import com.mall.entity.Promotion;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 营销活动数据访问层（阶段 8）。
 */
@Mapper
public interface PromotionRepository {

    /** 新增活动（主键回填） */
    @Insert("""
            INSERT INTO promotions (name, type, threshold, amount, discount_rate,
                                    rules, start_time, end_time, status)
            VALUES (#{name}, #{type}, #{threshold}, #{amount}, #{discountRate},
                    #{rules}, #{startTime}, #{endTime}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Promotion promotion);

    /** 更新活动（管理端编辑） */
    @Update("""
            UPDATE promotions
            SET name = #{name}, type = #{type}, threshold = #{threshold},
                amount = #{amount}, discount_rate = #{discountRate},
                rules = #{rules}, start_time = #{startTime}, end_time = #{endTime},
                status = #{status}
            WHERE id = #{id}
            """)
    int update(Promotion promotion);

    /** 删除活动（管理端） */
    @Delete("DELETE FROM promotions WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /** 按主键查活动 */
    @Select("SELECT * FROM promotions WHERE id = #{id}")
    Promotion selectById(@Param("id") Long id);

    /** 管理端活动列表（全部，分页，按创建时间倒序） */
    @Select("""
            SELECT * FROM promotions
            ORDER BY id DESC
            LIMIT #{offset}, #{pageSize}
            """)
    List<Promotion> selectPage(@Param("offset") int offset, @Param("pageSize") int pageSize);

    /** 管理端活动总数 */
    @Select("SELECT COUNT(*) FROM promotions")
    long countAll();

    /** 查询进行中的启用活动（start_time <= now <= end_time，类型不限），下单自动参与用 */
    @Select("""
            SELECT * FROM promotions
            WHERE status = 1
              AND start_time <= #{now}
              AND end_time >= #{now}
            ORDER BY id DESC
            """)
    List<Promotion> selectActive(@Param("now") LocalDateTime now);
}
