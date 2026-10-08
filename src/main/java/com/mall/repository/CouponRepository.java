package com.mall.repository;

import com.mall.entity.Coupon;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 优惠券模板数据访问层（阶段 8）。
 */
@Mapper
public interface CouponRepository {

    /** 新增模板（主键回填） */
    @Insert("""
            INSERT INTO coupons (name, type, threshold, amount, discount_rate,
                                 total_quantity, per_user_limit, status, valid_from, valid_until)
            VALUES (#{name}, #{type}, #{threshold}, #{amount}, #{discountRate},
                    #{totalQuantity}, #{perUserLimit}, #{status}, #{validFrom}, #{validUntil})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Coupon coupon);

    /** 更新模板（管理端编辑） */
    @Update("""
            UPDATE coupons
            SET name = #{name}, type = #{type}, threshold = #{threshold},
                amount = #{amount}, discount_rate = #{discountRate},
                total_quantity = #{totalQuantity}, per_user_limit = #{perUserLimit},
                status = #{status}, valid_from = #{validFrom}, valid_until = #{validUntil}
            WHERE id = #{id}
            """)
    int update(Coupon coupon);

    /** 删除模板（管理端；已领取的券数据保留，只是模板不可再领） */
    @Delete("DELETE FROM coupons WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /** 按主键查模板 */
    @Select("SELECT * FROM coupons WHERE id = #{id}")
    Coupon selectById(@Param("id") Long id);

    /** 管理端模板列表（全部，分页；附带"已领取数量"子查询，管理端展示发放进度） */
    @Select("""
            SELECT c.*,
                   (SELECT COUNT(*) FROM user_coupons uc WHERE uc.coupon_id = c.id) AS received_count
            FROM coupons c
            ORDER BY c.id DESC
            LIMIT #{offset}, #{pageSize}
            """)
    List<Coupon> selectPage(@Param("offset") int offset, @Param("pageSize") int pageSize);

    /** 管理端模板总数 */
    @Select("SELECT COUNT(*) FROM coupons")
    long countAll();

    /**
     * 领券中心列表（用户端）：上架 + 未过期 + 未开始可预约展示，
     * 附带"已领取数量"子查询（领完即止的剩余量展示）。
     */
    @Select("""
            SELECT c.*,
                   (SELECT COUNT(*) FROM user_coupons uc WHERE uc.coupon_id = c.id) AS received_count
            FROM coupons c
            WHERE c.status = 1
              AND c.valid_until > NOW()
            ORDER BY c.id DESC
            """)
    List<Coupon> selectCenter();

    /** 某模板已领取总数量（总量校验用） */
    @Select("SELECT COUNT(*) FROM user_coupons WHERE coupon_id = #{couponId}")
    long countReceived(@Param("couponId") Long couponId);
}
