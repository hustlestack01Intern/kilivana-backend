package com.kilivana.security;

import com.kilivana.security.config.ProxyProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

@Component
public class ClientIpResolver {

    private final ProxyProperties properties;
    private final List<IpAddressMatcher> trustedProxies;

    public ClientIpResolver(ProxyProperties properties) {
        properties.validate();
        this.properties = properties;
        this.trustedProxies = properties.trustedProxies().stream()
                .map(IpAddressMatcher::new)
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (!properties.trustForwardedHeaders() || !isTrusted(remoteAddress)) {
            return remoteAddress;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return remoteAddress;
        }
        String[] candidates = forwardedFor.split(",");
        String fallback = remoteAddress;
        for (int index = candidates.length - 1; index >= 0; index--) {
            String candidate = normalizeAddress(candidates[index]);
            if (candidate == null) {
                continue;
            }
            fallback = candidate;
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        return fallback;
    }

    private boolean isTrusted(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        return trustedProxies.stream().anyMatch(matcher -> matcher.matches(address));
    }

    private boolean isIpv4(String address) {
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                return false;
            }
            try {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return true;
    }

    private String normalizeAddress(String value) {
        if (value == null) {
            return null;
        }
        String address = value.trim();
        if (address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1);
        }
        int zoneIndex = address.indexOf('%');
        if (zoneIndex >= 0) {
            address = address.substring(0, zoneIndex);
        }
        if (!address.matches("[0-9a-fA-F:.]+")) {
            return null;
        }
        if (!address.contains(":") && !isIpv4(address)) {
            return null;
        }
        try {
            return InetAddress.getByName(address).getHostAddress();
        } catch (UnknownHostException exception) {
            return null;
        }
    }
}
