package com.mall.service;

import com.mall.common.BusinessException;
import com.mall.common.JwtUtil;
import com.mall.dto.LoginDTO;
import com.mall.dto.LoginResult;
import com.mall.dto.RegisterDTO;
import com.mall.dto.UserVO;
import com.mall.entity.User;
import com.mall.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 认证服务：注册、登录、登出、查询当前登录用户。
 *
 * <p>登录成功后把 token 写入 Redis 白名单（key: {@code auth:token:<token>}），
 * 由 JwtAuthFilter 校验；登出时删除该 key，实现"登出即失效"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** Redis 中 token 白名单的 key 前缀（与 JwtAuthFilter 保持一致） */
    private static final String TOKEN_KEY_PREFIX = "auth:token:";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;

    /** token 有效期（毫秒，由配置注入；写 Redis 时换算为秒） */
    @Value("${jwt.expiration}")
    private Long expiration;

    /**
     * 用户注册。
     *
     * @param dto 注册参数（用户名、密码、可选手机号/邮箱）
     * @return 注册成功后的用户信息（不含密码）
     */
    @Transactional
    public UserVO register(RegisterDTO dto) {
        // 1. 用户名唯一性校验
        if (userRepository.findByUsername(dto.getUsername()) != null) {
            throw new BusinessException(2001, "用户名已存在");
        }
        // 2. 手机号唯一性校验（注册时填写了手机号才校验）
        if (StringUtils.hasText(dto.getPhone()) && userRepository.findByPhone(dto.getPhone()) != null) {
            throw new BusinessException(2002, "手机号已被注册");
        }

        // 3. 组装实体：密码 BCrypt 加密，默认状态为正常、角色为普通用户
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setPhone(dto.getPhone());
        user.setEmail(dto.getEmail());
        user.setStatus(1);
        user.setRole("USER"); // 注册的账号一律为普通用户；管理员角色由数据库维护/另行授予
        user.setCreatedAt(LocalDateTime.now());

        // 4. 落库（自增主键回填到 user.id）
        userRepository.insert(user);
        log.info("新用户注册成功: id={}, username={}", user.getId(), user.getUsername());
        return toVO(user);
    }

    /**
     * 用户登录。
     *
     * <p>账号支持用户名或手机号；校验通过后签发 JWT 并写入 Redis 白名单。
     *
     * @param dto 登录参数
     * @return token 与用户信息
     */
    public LoginResult login(LoginDTO dto) {
        // 1. 按账号（用户名或手机号）查找用户
        User user = userRepository.findByUsername(dto.getAccount());
        if (user == null && StringUtils.hasText(dto.getAccount())) {
            user = userRepository.findByPhone(dto.getAccount());
        }

        // 2. 统一提示"用户名或密码错误"，不暴露具体是哪种不存在（防枚举攻击）
        if (user == null) {
            throw new BusinessException(BusinessException.UNAUTHORIZED, "用户名或密码错误");
        }

        // 3. 密码校验
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(BusinessException.UNAUTHORIZED, "用户名或密码错误");
        }

        // 4. 账号状态校验
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(BusinessException.FORBIDDEN, "账号已被禁用，请联系客服");
        }

        // 5. 签发 JWT 并写入 Redis 白名单（TTL 与 token 有效期一致）
        //    阶段 5：token 中携带角色（USER/ADMIN），管理端接口按角色鉴权
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        stringRedisTemplate.opsForValue().set(
                TOKEN_KEY_PREFIX + token,
                String.valueOf(user.getId()),
                expiration, TimeUnit.MILLISECONDS);

        log.info("用户登录成功: id={}, username={}", user.getId(), user.getUsername());
        return new LoginResult(token, toVO(user));
    }

    /**
     * 用户登出：删除 Redis 白名单中的 token，使其立即失效。
     *
     * @param token 前端请求头携带的原始 token（可能为 null）
     */
    public void logout(String token) {
        if (StringUtils.hasText(token)) {
            stringRedisTemplate.delete(TOKEN_KEY_PREFIX + token);
            log.info("用户登出，token 已失效");
        }
    }

    /**
     * 根据用户 ID 查询当前登录用户信息。
     *
     * @param userId 当前登录用户 ID（来自 SecurityContext）
     * @return 用户信息；用户不存在（如被删除）返回 null
     */
    public UserVO me(Long userId) {
        User user = userRepository.findById(userId);
        return user == null ? null : toVO(user);
    }

    /**
     * 实体转 VO：剔除 password 等敏感字段。
     */
    private UserVO toVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        vo.setAvatar(user.getAvatar());
        vo.setStatus(user.getStatus());
        vo.setRole(user.getRole());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }
}
