package com.rover.admin.agent.model;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型配置的落盘位置与主密钥来源。
 *
 * 默认写到工作目录下的 {@code config/}，与 Gateway / Nameserver 的本地运行时配置放在一起，
 * 随项目一起维护与备份；整个 {@code config/} 已在 {@code .gitignore} 中，密钥不会被误提交。
 * 主密钥也可以用环境变量 {@code ROVER_ADMIN_MASTER_KEY} 覆盖（宽松绑定到 {@code master-key}）。
 */
@ConfigurationProperties(prefix = AdminModelProperties.PREFIX)
public class AdminModelProperties {

    public static final String PREFIX = "rover.admin.model";

    /** 默认配置目录；相对路径按进程工作目录解析。 */
    private static final String DEFAULT_DIRECTORY = "config";

    /** 模型配置文件路径；留空用工作目录下 {@code config/admin-model.properties}。 */
    private String configFile = "";
    /** 主密钥（口令或 base64 32 字节）；留空则用主密钥文件。 */
    private String masterKey = "";
    /** 主密钥文件路径；留空用配置文件同级的 {@code master.key}。 */
    private String masterKeyFile = "";

    /** 配置文件绝对路径。 */
    public Path configFilePath() {
        if (hasText(configFile)) {
            return Path.of(configFile.trim()).toAbsolutePath().normalize();
        }
        return Path.of(DEFAULT_DIRECTORY, "admin-model.properties").toAbsolutePath().normalize();
    }

    /** 主密钥文件绝对路径。 */
    public Path masterKeyPath() {
        if (hasText(masterKeyFile)) {
            return Path.of(masterKeyFile.trim()).toAbsolutePath().normalize();
        }
        return configFilePath().resolveSibling("master.key");
    }

    public String getConfigFile() {
        return configFile;
    }

    public void setConfigFile(String configFile) {
        this.configFile = configFile;
    }

    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }

    public String getMasterKeyFile() {
        return masterKeyFile;
    }

    public void setMasterKeyFile(String masterKeyFile) {
        this.masterKeyFile = masterKeyFile;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}