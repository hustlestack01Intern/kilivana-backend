package com.kilivana.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Converts the PostgreSQL connection URIs that hosting providers hand out into the
 * {@code jdbc:} form the driver requires, and recovers the credentials those URIs embed.
 *
 * <p>Render exposes {@code DATABASE_URL} as {@code postgres://user:pass@host:5432/db},
 * Railway and Fly.io do the same, and Heroku uses {@code postgresql://}. Passed to the
 * driver unchanged, every one of those aborts startup with
 * {@code IllegalArgumentException: URL must start with 'jdbc'}. The credentials they carry
 * also have to be lifted into {@code spring.datasource.username} /
 * {@code spring.datasource.password}, because the driver never reads them out of the URL.
 */
public final class JdbcUrls {

    private static final String JDBC_PREFIX = "jdbc:";
    private static final String JDBC_POSTGRES_PREFIX = "jdbc:postgresql:";

    private JdbcUrls() {
    }

    /**
     * The JDBC equivalent of a connection URL, plus any credentials the URL carried.
     * Values that are already JDBC URLs, or that use an unrecognised scheme, are returned
     * unchanged with no credentials so the driver keeps reporting the real cause.
     */
    public record Parsed(String jdbcUrl, String username, String password) {
    }

    public static Parsed parse(String value) {
        if (value == null) {
            return new Parsed(null, null, null);
        }
        String trimmed = value.trim();
        if (trimmed.regionMatches(true, 0, JDBC_PREFIX, 0, JDBC_PREFIX.length())) {
            return new Parsed(trimmed, null, null);
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("postgres://") && !lower.startsWith("postgresql://")) {
            return new Parsed(trimmed, null, null);
        }
        URI uri = parseUri(trimmed);
        if (uri == null || uri.getHost() == null) {
            // Both malformations providers actually emit are rejected by URI itself: a
            // bracketed IPv4 authority ("[192.168.1.10]") is not a legal host, and an
            // unencoded "%" in a password is not a legal escape. Both used to be passed
            // through untouched, so the driver died with "URL must start with 'jdbc'" and
            // the credentials the URL carried were lost on the way.
            String repaired = repair(trimmed);
            if (repaired == null) {
                // Nothing left to repair. Returning the value unchanged reported the failure
                // as a missing "jdbc:" prefix, discarded the credentials, and left the
                // operator reading an authentication error caused by a malformed host.
                throw malformed(trimmed);
            }
            return parse(repaired);
        }
        String host = uri.getHost();
        // URI.getHost() hands back an IPv6 literal *with* its brackets ("[::1]"), so
        // re-wrapping it below produced "[[::1]]" and the driver resolved a host that
        // cannot exist. Strip first, then re-apply exactly once.
        String bareHost = stripBrackets(host);
        StringBuilder jdbc = new StringBuilder(JDBC_POSTGRES_PREFIX).append("//");
        // The userinfo is deliberately dropped from the JDBC URL. The PostgreSQL driver
        // splits host:port at the last colon and treats everything before it as the host,
        // so "postgres://user:pw@host/db" becomes the unresolvable host "user:pw@host".
        // Credentials travel as spring.datasource.username / .password instead.
        if (bareHost.contains(":")) {
            jdbc.append('[').append(bareHost).append(']');
        } else {
            jdbc.append(bareHost);
        }
        if (uri.getPort() != -1) {
            jdbc.append(':').append(uri.getPort());
        }
        String path = uri.getRawPath();
        jdbc.append(path == null || path.isEmpty() ? "/" : path);
        if (uri.getRawQuery() != null) {
            jdbc.append('?').append(uri.getRawQuery());
        }
        String[] credentials = splitCredentials(uri.getRawUserInfo());
        return new Parsed(jdbc.toString(), credentials[0], credentials[1]);
    }

    /**
     * Returns {@code value} as a JDBC URL, discarding any credentials it carried.
     * Values that already start with {@code jdbc:} are returned unchanged; anything using
     * an unrecognised scheme is returned unchanged so the driver keeps reporting the cause.
     */
    public static String toJdbcUrl(String value) {
        return parse(value).jdbcUrl();
    }

    private static String stripBrackets(String host) {
        if (host.length() > 1 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static String[] splitCredentials(String rawUserInfo) {
        if (rawUserInfo == null || rawUserInfo.isEmpty()) {
            return new String[] {null, null};
        }
        int separator = rawUserInfo.indexOf(':');
        if (separator < 0) {
            return new String[] {decode(rawUserInfo), null};
        }
        return new String[] {
            decode(rawUserInfo.substring(0, separator)),
            decode(rawUserInfo.substring(separator + 1))
        };
    }

    /**
     * A URI userinfo is percent-encoded, not form-encoded, so this decodes escapes and leaves
     * every other character alone.
     *
     * <p>{@code URLDecoder} is the wrong tool twice over here: it throws
     * {@link IllegalArgumentException} on a truncated or non-hex escape, and it rewrites "+"
     * into a space. These strings are machine-generated, and a generated password containing
     * a literal "+" is common enough that silently substituting a space yields an
     * authentication failure nobody can explain. An escape that does not parse is kept as the
     * literal "%" it is.
     */
    private static String decode(String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value;
        }
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(value.length());
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            int high = index + 2 < value.length() ? Character.digit(value.charAt(index + 1), 16) : -1;
            int low = index + 2 < value.length() ? Character.digit(value.charAt(index + 2), 16) : -1;
            if (current == '%' && high >= 0 && low >= 0) {
                decoded.write((high << 4) + low);
                index += 3;
            } else {
                decoded.writeBytes(String.valueOf(current).getBytes(StandardCharsets.UTF_8));
                index++;
            }
        }
        return decoded.toString(StandardCharsets.UTF_8);
    }

    private static URI parseUri(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    /**
     * The repairs that make a provider URL parse, or {@code null} when neither applies. Both
     * are idempotent, so the recursive call back into {@link #parse(String)} terminates.
     *
     * <p>The two repairs are composed rather than short-circuited. A URL that needs only the
     * unbracket — a bracketed IPv4 literal, the common one, which carries no "%" at all — used
     * to be reported as unrepairable, because the percent-escape pass returns {@code null} when
     * there is no percent sign to escape and {@code null} was being read as "nothing to do".
     */
    private static String repair(String value) {
        String unbracketed = unbracketHost(value);
        String candidate = unbracketed == null ? value : unbracketed;
        String unescaped = unescaped(candidate);
        if (unescaped != null) {
            candidate = unescaped;
        }
        return candidate.equals(value) ? null : candidate;
    }

    /**
     * A URL that is a {@code postgres://} URI in form and unusable in substance. The value is
     * never interpolated: it carries the database password, and this exception is rendered by
     * Spring's failure analyzer and written to the deploy log.
     */
    private static IllegalArgumentException malformed(String value) {
        return new IllegalArgumentException(
                "The database connection URL is malformed: " + malformationOf(value)
                        + ". Set DB_URL (or DATABASE_URL) to postgres://USER:PASSWORD@HOST:5432/DATABASE. "
                        + "Only an IPv6 host takes brackets, and a '%' inside the password must be "
                        + "percent-encoded as %25. The value is not repeated here because it carries "
                        + "the password.");
    }

    /**
     * Names the structural defect. Every branch is decided from the value and none of them
     * quote it, so a message can never leak the credentials the URL carried.
     */
    private static String malformationOf(String value) {
        if (value.chars().anyMatch(Character::isWhitespace)) {
            return "it contains whitespace";
        }
        int schemeEnd = value.indexOf("://");
        int authorityStart = schemeEnd < 0 ? -1 : schemeEnd + 3;
        if (authorityStart < 0) {
            return "the scheme separator is missing";
        }
        int authorityEnd = authorityEnd(value, authorityStart);
        String authority = authorityEnd < 0
                ? value.substring(authorityStart)
                : value.substring(authorityStart, authorityEnd);
        int at = authority.lastIndexOf('@');
        String hostAndPort = at < 0 ? authority : authority.substring(at + 1);
        int portStart = hostAndPort.lastIndexOf(':');
        String host = portStart < 0 ? hostAndPort : hostAndPort.substring(0, portStart);
        if (host.isEmpty()) {
            return "the authority carries no host";
        }
        if (host.startsWith("[") != host.endsWith("]")) {
            return "the host has an unbalanced bracket";
        }
        return "its host is neither a legal IPv4 address nor a bracketed IPv6 literal";
    }

    /**
     * Every "%" that is not the head of a valid two-hex-digit escape, re-escaped as "%25" so
     * that {@link #decode(String)} yields the literal character it was. Escapes URI already
     * accepts are left exactly as they are.
     */
    private static String unescaped(String value) {
        if (value.indexOf('%') < 0) {
            return null;
        }
        StringBuilder repaired = new StringBuilder(value);
        int index = 0;
        while (index < repaired.length()) {
            boolean isHeadOfEscape = index + 2 < repaired.length()
                    && Character.digit(repaired.charAt(index + 1), 16) >= 0
                    && Character.digit(repaired.charAt(index + 2), 16) >= 0;
            if (repaired.charAt(index) != '%' || isHeadOfEscape) {
                index++;
                continue;
            }
            repaired.replace(index, index + 1, "%25");
            // Step over the two characters just rejected, so a run of "%" is escaped one
            // character at a time rather than re-examined.
            index += 3;
        }
        return repaired.toString();
    }

    /**
     * The same URL with a bracketed, colon-free host unwrapped, or {@code null} when there is
     * nothing to unwrap. Single pass: the authority ends at the first '/', '?' or '#', and
     * the credentials end at the last '@' before it.
     */
    private static String unbracketHost(String value) {
        int schemeEnd = value.indexOf("://");
        if (schemeEnd < 0) {
            return null;
        }
        int authorityStart = schemeEnd + 3;
        int authorityEnd = authorityEnd(value, authorityStart);
        if (authorityEnd < 0) {
            return null;
        }
        String authority = value.substring(authorityStart, authorityEnd);
        int at = authority.lastIndexOf('@');
        String userInfo = at < 0 ? "" : authority.substring(0, at + 1);
        String hostAndPort = authority.substring(at + 1);

        if (hostAndPort.isEmpty() || hostAndPort.charAt(0) != '[') {
            return null;
        }
        int close = hostAndPort.indexOf(']');
        if (close < 0) {
            return null;
        }
        String bracketed = hostAndPort.substring(1, close);
        if (bracketed.isEmpty() || bracketed.indexOf(':') >= 0) {
            return null;
        }
        return value.substring(0, authorityStart) + userInfo + hostAndPort.substring(1, close)
                + hostAndPort.substring(close + 1) + value.substring(authorityEnd);
    }

    private static int authorityEnd(String value, int from) {
        for (int index = from; index < value.length(); index++) {
            char candidate = value.charAt(index);
            if (candidate == '/' || candidate == '?' || candidate == '#') {
                return index;
            }
        }
        return -1;
    }
}
