package com.mall.repository;

import com.mall.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户数据访问层（MyBatis Mapper）。
 *
 * <p>采用注解 SQL，避免为简单 CRUD 维护 XML 文件；
 * 复杂查询（多表、动态条件）后续阶段再落到
 * {@code resources/mapper/*.xml}（application.yml 已配置扫描路径）。
 */
@Mapper
public interface UserRepository {

    /** 按用户名查询（用于登录与唯一性校验） */
    @Select("SELECT * FROM users WHERE username = #{username}")
    User findByUsername(@Param("username") String username);

    /** 按手机号查询（用于手机号登录与唯一性校验） */
    @Select("SELECT * FROM users WHERE phone = #{phone}")
    User findByPhone(@Param("phone") String phone);

    /** 按主键查询 */
    @Select("SELECT * FROM users WHERE id = #{id}")
    User findById(@Param("id") Long id);

    /**
     * 新增用户。
     *
     * @param user 需包含 username/password，其余字段可空
     * @return 影响行数；自增主键回填到 user.id
     */
    @Insert("""
            INSERT INTO users (username, password, phone, email, avatar, status, created_at)
            VALUES (#{username}, #{password}, #{phone}, #{email}, #{avatar}, #{status}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    /**
     * 更新个人资料（仅允许修改 email / avatar / phone，用户名不可改）。
     *
     * @param user 只需携带 id 与待更新字段
     * @return 影响行数
     */
    @Update("""
            UPDATE users
            SET email = #{email},
                avatar = #{avatar},
                phone = #{phone}
            WHERE id = #{id}
            """)
    int updateProfile(User user);
}
