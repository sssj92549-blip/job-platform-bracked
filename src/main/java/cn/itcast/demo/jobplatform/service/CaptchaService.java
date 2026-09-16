package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.common.BusinessException;
import cn.itcast.demo.jobplatform.vo.AuthViews;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 图形验证码仅保存于服务端Session，120秒有效，验证时一次性消费。
 */
@Service
public class CaptchaService {
    static final String KEY = "auth.captcha";
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private final SecureRandom random = new SecureRandom();

    record Challenge(String id, String answer, long expiresAt) implements Serializable {
    }

    public AuthViews.Captcha create(HttpSession session) {
        StringBuilder answer = new StringBuilder();
        for (int i = 0; i < 4; i++) answer.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        String id = SessionSupport.token();
        BufferedImage image = new BufferedImage(160, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(239, 246, 236));
            g.fillRect(0, 0, 160, 48);
            for (int i = 0; i < 10; i++) {
                g.setColor(new Color(110 + random.nextInt(100), 140 + random.nextInt(90), 130 + random.nextInt(100)));
                g.drawLine(random.nextInt(160), random.nextInt(48), random.nextInt(160), random.nextInt(48));
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
            g.setColor(new Color(30, 95, 78));
            for (int i = 0; i < 4; i++) g.drawString(answer.substring(i, i + 1), 14 + i * 35, 33 + random.nextInt(7));
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            synchronized (session) {
                session.setAttribute(KEY, new Challenge(id, answer.toString(), System.currentTimeMillis() + 120000));
            }
            return new AuthViews.Captcha(id, "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray()), 120, SessionSupport.csrf(session));
        } catch (IOException e) {
            throw new IllegalStateException("验证码生成失败", e);
        }
    }

    /**
     * 无论答对与否都消费验证码，防止对同一图片连续猜测。
     */
    public void verify(HttpSession session, String id, String answer) {
        synchronized (session) {
            Challenge c = (Challenge) session.getAttribute(KEY);
            session.removeAttribute(KEY);
            if (c == null || !c.id().equals(id) || c.expiresAt() < System.currentTimeMillis() || !c.answer().equalsIgnoreCase(answer.trim()))
                throw new BusinessException(HttpStatus.BAD_REQUEST, 40002, "图形验证码错误或已过期，请重新获取");
        }
    }
}
