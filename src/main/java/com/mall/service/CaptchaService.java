package com.mall.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 图形验证码服务（Phase 1 安全加固）。
 *
 * <p>登录接口加图形验证码，阻断"密码爆破/撞库"类自动化攻击：
 * <ol>
 *   <li>前端请求 {@code GET /api/auth/captcha} 获取验证码图片 + captchaId；</li>
 *   <li>后端用 Java2D 绘制 4 位字符图片（带噪点/干扰线），
 *       答案存 Redis（key: {@code captcha:{captchaId}}，TTL 5 分钟）；</li>
 *   <li>登录时携带 captchaId + captchaCode，本服务比对后<b>立即删除</b>
 *       （一次性使用，防重放）；</li>
 *   <li>Redis 取不到（过期/已用）或比对失败 → 校验不通过。</li>
 * </ol>
 *
 * <p>字符集刻意剔除易混淆字符（0/O、1/l），降低误输率。
 * 开关 {@code security.captcha-enabled}：false 时 verify 直接放行（测试/联调用）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptchaService {

    /** 验证码 Redis key 前缀 */
    private static final String KEY_PREFIX = "captcha:";
    /** 验证码有效期（秒） */
    private static final long TTL_SECONDS = 300L;
    /** 验证码字符集（剔除 0/O/1/l/I 等易混淆字符） */
    private static final char[] CHARS =
            "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz".toCharArray();
    /** 验证码长度 */
    private static final int LENGTH = 4;
    /** 图片尺寸（宽高，像素） */
    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;

    private final StringRedisTemplate stringRedisTemplate;
    private final SecureRandom random = new SecureRandom();

    /** 验证码开关（yml 配置；false 时跳过校验，便于自动化测试与联调） */
    @Value("${security.captcha-enabled:true}")
    private boolean captchaEnabled;

    /**
     * 生成验证码：返回 { captchaId, imageBase64 }。
     * 答案只进 Redis，不回传前端（前端拿不到答案，只能靠人眼识别图片）。
     */
    public Map<String, String> generate() {
        // 1. 生成随机答案
        String code = randomCode();

        // 2. 画验证码图片（噪点 + 干扰线 + 随机颜色/旋转，增加 OCR 识别难度）
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(245, 245, 245));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        // 干扰线
        for (int i = 0; i < 5; i++) {
            g.setColor(new Color(random.nextInt(200), random.nextInt(200), random.nextInt(200)));
            g.setStroke(new BasicStroke(1.2f));
            g.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                    random.nextInt(WIDTH), random.nextInt(HEIGHT));
        }
        // 字符（逐个绘制，轻微偏移 + 随机颜色）
        Font font = new Font("SansSerif", Font.BOLD, 24);
        g.setFont(font);
        for (int i = 0; i < code.length(); i++) {
            g.setColor(new Color(30 + random.nextInt(120), 30 + random.nextInt(120), 30 + random.nextInt(120)));
            int x = 12 + i * 26;
            int y = 28 + random.nextInt(6);
            g.drawString(String.valueOf(code.charAt(i)), x, y);
        }
        // 噪点
        for (int i = 0; i < 40; i++) {
            g.setColor(new Color(random.nextInt(200), random.nextInt(200), random.nextInt(200)));
            g.fillRect(random.nextInt(WIDTH), random.nextInt(HEIGHT), 1, 1);
        }
        g.dispose();

        // 3. 答案存 Redis（TTL 5 分钟），图片转 Base64 返回
        String captchaId = "c" + System.nanoTime() + random.nextInt(10000);
        stringRedisTemplate.opsForValue().set(
                KEY_PREFIX + captchaId, code, TTL_SECONDS, TimeUnit.SECONDS);

        String imageBase64;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            imageBase64 = "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("验证码图片生成失败", e);
        }

        Map<String, String> result = new LinkedHashMap<>();
        result.put("captchaId", captchaId);
        result.put("imageBase64", imageBase64);
        return result;
    }

    /**
     * 校验验证码（一次性：无论成败都删除，防止重放）。
     *
     * @param captchaId   前端携带的验证码 ID
     * @param captchaCode 用户输入的验证码
     * @return 校验是否通过
     */
    public boolean verify(String captchaId, String captchaCode) {
        // 开关关闭 → 直接放行（测试/联调模式）
        if (!captchaEnabled) {
            return true;
        }
        if (!StringUtils.hasText(captchaId) || !StringUtils.hasText(captchaCode)) {
            return false;
        }
        String key = KEY_PREFIX + captchaId;
        String expected = stringRedisTemplate.opsForValue().get(key);
        // 无论比对结果如何都删除：验证码只能使用一次
        stringRedisTemplate.delete(key);
        return expected != null && expected.equalsIgnoreCase(captchaCode.trim());
    }

    /** 生成随机 4 位验证码文本 */
    private String randomCode() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(CHARS[random.nextInt(CHARS.length)]);
        }
        return sb.toString();
    }
}
