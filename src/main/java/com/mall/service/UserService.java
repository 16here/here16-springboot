package com.mall.service;

import com.mall.common.BusinessException;
import com.mall.dto.UpdateProfileDTO;
import com.mall.dto.UserVO;
import com.mall.entity.User;
import com.mall.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户服务：个人资料查询与修改。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /**
     * 查询个人资料。
     *
     * @param userId 当前登录用户 ID
     * @return 用户信息（不含密码）
     */
    public UserVO getProfile(Long userId) {
        User user = requireUser(userId);
        return toVO(user);
    }

    /**
     * 修改个人资料（仅允许修改邮箱、头像、手机号）。
     *
     * @param userId 当前登录用户 ID
     * @param dto    待更新字段
     * @return 更新后的用户信息
     */
    @Transactional
    public UserVO updateProfile(Long userId, UpdateProfileDTO dto) {
        User user = requireUser(userId);

        // 手机号若修改，需保持全局唯一（排除当前用户自身）
        if (StringUtils.hasText(dto.getPhone()) && !dto.getPhone().equals(user.getPhone())) {
            User exist = userRepository.findByPhone(dto.getPhone());
            if (exist != null && !exist.getId().equals(userId)) {
                throw new BusinessException(2002, "手机号已被其他账号使用");
            }
        }

        // 空值策略：null 表示"不修改"，保留原值；字段有值时更新
        user.setEmail(StringUtils.hasText(dto.getEmail()) ? dto.getEmail() : user.getEmail());
        user.setAvatar(StringUtils.hasText(dto.getAvatar()) ? dto.getAvatar() : user.getAvatar());
        user.setPhone(StringUtils.hasText(dto.getPhone()) ? dto.getPhone() : user.getPhone());

        userRepository.updateProfile(user);
        log.info("用户资料更新成功: id={}", userId);
        return toVO(user);
    }

    /**
     * 加载用户并校验存在。
     */
    private User requireUser(Long userId) {
        User user = userRepository.findById(userId);
        if (user == null) {
            throw new BusinessException(BusinessException.UNAUTHORIZED, "用户不存在或已注销");
        }
        return user;
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
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }
}
