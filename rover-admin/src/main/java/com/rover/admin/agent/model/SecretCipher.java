package com.rover.admin.agent.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * API 密钥的落盘保护：AES-256-GCM，密文格式为 {@code base64(IV || 密文)}。
 *
 * 主密钥解析顺序：显式配置（环境变量 {@code ROVER_ADMIN_MASTER_KEY} 或
 * {@code rover.admin.model.master-key}，base64 解出 32 字节则直接用，否则按口令 PBKDF2 派生）
 * → 主密钥文件 → 首次加密时随机生成并写入主密钥文件。
 *
 * 这只是"落盘静态保护"，不是 KMS：主密钥文件必须与模型配置文件**分开备份**，
 * 两者放在一起等于没有加密。Windows 没有 POSIX 权限，机密性靠加密而不是文件模式。
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 65_536;
    private static final byte[] PBKDF2_SALT = "rover-admin-model-key".getBytes(StandardCharsets.UTF_8);

    private final String configuredKey;
    private final Path masterKeyFile;
    private final SecureRandom random = new SecureRandom();
    private volatile SecretKey key;

    /** 容器装配用；另一个构造器留给测试直接指定主密钥与文件位置。 */
    @Autowired
    public SecretCipher(AdminModelProperties properties) {
        this(properties.getMasterKey(), properties.masterKeyPath());
    }

    public SecretCipher(String configuredKey, Path masterKeyFile) {
        this.configuredKey = configuredKey;
        this.masterKeyFile = masterKeyFile;
    }

    /** 加密；没有主密钥时首次生成并落盘。主密钥写不出去会抛异常，让保存失败可见。 */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return "";
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keyForEncrypt(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(sealed, 0, payload, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("API 密钥加密失败：" + ex.getMessage(), ex);
        }
    }

    /** 解密；主密钥缺失或密文不匹配时抛异常，由调用方转为"密钥无法解密"。 */
    public String decrypt(String encoded) {
        SecretKey current = existingKey();
        if (current == null) {
            throw new IllegalStateException("主密钥不可用");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(encoded);
            if (payload.length <= IV_BYTES) {
                throw new IllegalArgumentException("密文长度不合法");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, current, new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("密文无法解密（主密钥变更或内容损坏）", ex);
        }
    }

    /** 写文件前先解析已有主密钥，没有就生成一个。 */
    private SecretKey keyForEncrypt() {
        SecretKey current = existingKey();
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (key == null) {
                byte[] raw = new byte[KEY_BYTES];
                random.nextBytes(raw);
                persistMasterKey(raw);
                key = new SecretKeySpec(raw, KEY_ALGORITHM);
            }
            return key;
        }
    }

    /** 已配置主密钥 → 主密钥文件；都没有返回 null。 */
    private SecretKey existingKey() {
        SecretKey current = key;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (key != null) {
                return key;
            }
            if (configuredKey != null && !configuredKey.isBlank()) {
                key = derive(configuredKey);
                return key;
            }
            SecretKey stored = readMasterKeyFile();
            if (stored != null) {
                key = stored;
            }
            return key;
        }
    }

    private SecretKey readMasterKeyFile() {
        if (masterKeyFile == null || !Files.isRegularFile(masterKeyFile)) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(Files.readString(masterKeyFile, StandardCharsets.UTF_8).trim());
            if (raw.length != KEY_BYTES) {
                log.warn("主密钥文件长度不是 {} 字节，已忽略：{}", KEY_BYTES, masterKeyFile);
                return null;
            }
            return new SecretKeySpec(raw, KEY_ALGORITHM);
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("主密钥文件无法读取，已忽略：{}（{}）", masterKeyFile, ex.getMessage());
            return null;
        }
    }

    private void persistMasterKey(byte[] raw) {
        try {
            Path parent = masterKeyFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(masterKeyFile, Base64.getEncoder().encodeToString(raw), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(masterKeyFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows 无 POSIX 权限，机密性由 AES-GCM 保证。
            }
            log.warn("已生成主密钥文件 {}；它必须与模型配置文件分开备份，放在一起等于没有加密。"
                    + "Windows 加固命令：icacls \"{}\" /inheritance:r /grant:r \"%USERNAME%:F\"",
                    masterKeyFile, masterKeyFile);
        } catch (IOException ex) {
            throw new IllegalStateException("主密钥无法写入 " + masterKeyFile + "：" + ex.getMessage(), ex);
        }
    }

    private static SecretKey derive(String configured) {
        String value = configured.trim();
        try {
            byte[] raw = Base64.getDecoder().decode(value);
            if (raw.length == KEY_BYTES) {
                return new SecretKeySpec(raw, KEY_ALGORITHM);
            }
        } catch (IllegalArgumentException ignored) {
            // 不是 base64，按口令派生。
        }
        try {
            PBEKeySpec spec = new PBEKeySpec(value.toCharArray(), PBKDF2_SALT, PBKDF2_ITERATIONS, KEY_BYTES * 8);
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(raw, KEY_ALGORITHM);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("主密钥无法派生：" + ex.getMessage(), ex);
        }
    }
}