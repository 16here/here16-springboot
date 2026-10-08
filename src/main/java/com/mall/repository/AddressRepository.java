package com.mall.repository;

import com.mall.entity.Address;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 收货地址数据访问层。
 *
 * <p>安全约定：所有按 id 的更新/删除/置默认都带 userId 条件，
 * 影响行数为 0 即代表"不存在或不属于该用户"，杜绝越权操作他人地址。
 */
@Mapper
public interface AddressRepository {

    /** 查询某用户全部地址（默认地址排最前，其余按创建时间倒序） */
    @Select("""
            SELECT * FROM addresses
            WHERE user_id = #{userId}
            ORDER BY is_default DESC, id DESC
            """)
    List<Address> selectByUserId(@Param("userId") Long userId);

    /** 按主键 + 用户查（归属校验） */
    @Select("SELECT * FROM addresses WHERE id = #{id} AND user_id = #{userId}")
    Address findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 统计某用户地址数量（首个地址自动设默认用） */
    @Select("SELECT COUNT(*) FROM addresses WHERE user_id = #{userId}")
    long countByUserId(@Param("userId") Long userId);

    /** 新增地址（主键回填） */
    @Insert("""
            INSERT INTO addresses (user_id, receiver_name, receiver_phone,
                                   province, city, district, detail, is_default)
            VALUES (#{userId}, #{receiverName}, #{receiverPhone},
                    #{province}, #{city}, #{district}, #{detail}, #{isDefault})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Address address);

    /** 更新地址（不允许改 userId 与 isDefault，默认状态单独用 setDefault 管理） */
    @Update("""
            UPDATE addresses
            SET receiver_name = #{receiverName},
                receiver_phone = #{receiverPhone},
                province = #{province},
                city = #{city},
                district = #{district},
                detail = #{detail}
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int update(@Param("id") Long id, @Param("userId") Long userId, Address address);

    /** 删除地址（带 userId 防越权） */
    @Delete("DELETE FROM addresses WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 把某用户全部地址置为非默认（设置默认地址前先清场） */
    @Update("UPDATE addresses SET is_default = 0 WHERE user_id = #{userId}")
    int clearDefault(@Param("userId") Long userId);

    /** 把某条地址置为默认（带 userId 防越权） */
    @Update("UPDATE addresses SET is_default = 1 WHERE id = #{id} AND user_id = #{userId}")
    int setDefault(@Param("id") Long id, @Param("userId") Long userId);
}
