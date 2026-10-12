package com.codeatlas.server.util;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.exception.BusinessException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;

/**
 * 项目源码来源安全校验：
 * - Git URL 禁止指向本机/内网地址（防 SSRF）；
 * - 本地路径必须位于允许的工作区根目录内（防任意路径扫描）。
 */
public final class SourceUrlValidator {

    private static final String GIT_URL_PATTERN = "^https?://[^\\s]+$";

    private SourceUrlValidator() {
    }

    /** Git 仓库 URL：格式校验 + 禁止本机/内网主机 */
    public static void validateGitUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Git 仓库 URL 不能为空");
        }
        if (!url.matches(GIT_URL_PATTERN)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Git URL 格式不正确，示例：https://github.com/user/repo.git");
        }
        if (isInternalHost(url)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不允许访问本机或内网地址");
        }
    }

    /** 本地源码路径：必须真实存在且位于 allowedRoot 之内，返回规范化真实路径 */
    public static Path resolveLocalPathInside(Path allowedRoot, String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "本地源码路径不能为空");
        }
        try {
            Path resolved = Path.of(rawPath).toRealPath();
            Path root = allowedRoot.toRealPath();
            if (!resolved.startsWith(root)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "本地路径超出允许范围（仅支持工作区内的上传目录）");
            }
            return resolved;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "本地源码路径无效或不存在");
        }
    }

    private static boolean isInternalHost(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return true;
            }
            String lowerHost = host.toLowerCase();
            if ("localhost".equals(lowerHost) || lowerHost.endsWith(".localhost")) {
                return true;
            }
            try {
                return isInternalAddress(InetAddress.getByName(host));
            } catch (UnknownHostException e) {
                // 域名暂时解析失败：放行，后续 clone 会自然失败
                return false;
            }
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean isInternalAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        // IPv6 唯一本地地址 fc00::/7
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
