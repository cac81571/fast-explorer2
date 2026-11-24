package com.fastexplorer.search;

import com.fastexplorer.model.EntryKind;
import com.fastexplorer.util.PathStrings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Include and exclude patterns for file names and for folder names along a relative path.
 * Tokens are separated by comma, semicolon, or whitespace. A token with {@code *} or {@code ?}
 * is a wildcard; any other token is a case-insensitive substring. An empty include list matches
 * everything. A pattern that contains a slash is matched against the whole relative path.
 */
public final class NameFilter {
    private static final NameFilter ALL = new NameFilter(List.of(), List.of(), List.of(), List.of());

    private final List<Token> fileInclude;
    private final List<Token> fileExclude;
    private final List<Token> dirInclude;
    private final List<Token> dirExclude;

    private NameFilter(List<Token> fileInclude, List<Token> fileExclude, List<Token> dirInclude, List<Token> dirExclude) {
        this.fileInclude = fileInclude;
        this.fileExclude = fileExclude;
        this.dirInclude = dirInclude;
        this.dirExclude = dirExclude;
    }

    public static NameFilter all() {
        return ALL;
    }

    public static NameFilter parse(String includeFiles, String excludeFiles, String includeDirs, String excludeDirs) {
        return new NameFilter(
                tokens(includeFiles),
                tokens(excludeFiles),
                tokens(includeDirs),
                tokens(excludeDirs));
    }

    public boolean hasFilePatterns() {
        return !fileInclude.isEmpty() || !fileExclude.isEmpty();
    }

    public boolean hasDirectoryPatterns() {
        return !dirInclude.isEmpty() || !dirExclude.isEmpty();
    }

    public List<String> fileIncludeLikes() {
        return likes(fileInclude);
    }

    public List<String> fileExcludeLikes() {
        return likes(fileExclude);
    }

    /** Anchored regex matching a relative directory path (no leading slash) when any include hits. */
    public String directoryIncludeRegex() {
        return combine(dirInclude);
    }

    public String directoryExcludeRegex() {
        return combine(dirExclude);
    }

    public boolean acceptsFileName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (!fileInclude.isEmpty() && fileInclude.stream().noneMatch(token -> token.matches(lower))) {
            return false;
        }
        return fileExclude.stream().noneMatch(token -> token.matches(lower));
    }

    /**
     * {@code relativePath} uses forward slashes and no leading slash.
     * Empty include passes. Exclude wins when any folder name on the path matches.
     */
    public boolean acceptsRelativeDirectory(String relativePath) {
        String normalized = normalize(relativePath);
        if (matchesAny(dirExclude, normalized)) {
            return false;
        }
        return dirInclude.isEmpty() || matchesAny(dirInclude, normalized);
    }

    public boolean accepts(String relativeParent, String name, EntryKind kind) {
        String parent = normalize(relativeParent);
        if (kind == EntryKind.DIRECTORY) {
            return acceptsRelativeDirectory(join(parent, name));
        }
        return acceptsFileName(name) && acceptsRelativeDirectory(parent);
    }

    /** True when this folder should not be entered by a search walk or the indexer. */
    public boolean excludesDirectory(String relativeParent, String folderName) {
        if (dirExclude.isEmpty() || folderName == null || folderName.isBlank()) {
            return false;
        }
        return matchesAny(dirExclude, join(normalize(relativeParent), folderName));
    }

    public static String relative(String rootKey, String dirKey) {
        if (rootKey == null || dirKey == null) {
            return "";
        }
        if (dirKey.equals(rootKey)) {
            return "";
        }
        String prefix = rootKey.endsWith("/") ? rootKey : rootKey + "/";
        if (dirKey.startsWith(prefix)) {
            return dirKey.substring(prefix.length());
        }
        return "";
    }

    private static boolean matchesAny(List<Token> tokens, String relativePath) {
        if (relativePath.isEmpty()) {
            return false;
        }
        String[] segments = relativePath.split("/");
        for (Token token : tokens) {
            if (token.path) {
                if (token.matches(relativePath)) {
                    return true;
                }
                continue;
            }
            for (String segment : segments) {
                if (!segment.isEmpty() && token.matches(segment)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String combine(List<Token> tokens) {
        if (tokens.isEmpty()) {
            return "";
        }
        StringBuilder regex = new StringBuilder();
        for (Token token : tokens) {
            if (!regex.isEmpty()) {
                regex.append('|');
            }
            regex.append(token.pathRegex());
        }
        return regex.toString();
    }

    private static List<String> likes(List<Token> tokens) {
        List<String> values = new ArrayList<>();
        for (Token token : tokens) {
            values.add(token.like());
        }
        return values;
    }

    private static List<Token> tokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Token> parsed = new ArrayList<>();
        for (String part : text.split("[,;、\\s]+")) {
            String token = part.trim().toLowerCase(Locale.ROOT);
            if (!token.isEmpty()) {
                parsed.add(new Token(token));
            }
        }
        return List.copyOf(parsed);
    }

    private static String normalize(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "";
        }
        String value = relativePath.replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/") && !value.isEmpty()) {
            value = value.substring(0, value.length() - 1);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String join(String parent, String name) {
        String child = name == null ? "" : name.trim();
        if (parent == null || parent.isEmpty()) {
            return child;
        }
        if (child.isEmpty()) {
            return parent;
        }
        return parent + "/" + child;
    }

    private static final class Token {
        private final String text;
        private final boolean wildcard;
        private final boolean path;
        private final Pattern regex;

        private Token(String lowerText) {
            this.text = lowerText;
            this.wildcard = lowerText.indexOf('*') >= 0 || lowerText.indexOf('?') >= 0;
            this.path = lowerText.indexOf('/') >= 0 || lowerText.indexOf('\\') >= 0;
            this.regex = wildcard ? Pattern.compile(regexBody(lowerText, path)) : null;
        }

        private boolean matches(String lowerCandidate) {
            if (!wildcard) {
                return lowerCandidate.contains(text);
            }
            return regex.matcher(lowerCandidate).matches();
        }

        private String like() {
            if (!wildcard) {
                return "%" + PathStrings.escapeLike(text) + "%";
            }
            StringBuilder like = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '*') {
                    like.append('%');
                } else if (c == '?') {
                    like.append('_');
                } else if (c == '%' || c == '_' || c == '\\') {
                    like.append('\\').append(c);
                } else {
                    like.append(c);
                }
            }
            return like.toString();
        }

        /** Anchored so H2 {@code REGEXP} (which searches) still matches the whole path or one segment. */
        private String pathRegex() {
            if (path) {
                return regexBody(text, true);
            }
            String segment = wildcard ? glob(text, false) : "[^/]*" + quote(text) + "[^/]*";
            return "^(?:[^/]*/)?" + "(?:[^/]*/)*" + segment + "(?:/[^/]*)*$";
        }

        private static String regexBody(String token, boolean slashAware) {
            return "^" + (token.indexOf('*') >= 0 || token.indexOf('?') >= 0
                    ? glob(token, slashAware)
                    : ".*" + quote(token) + ".*") + "$";
        }

        private static String glob(String token, boolean slashAware) {
            StringBuilder body = new StringBuilder();
            StringBuilder literal = new StringBuilder();
            for (int i = 0; i < token.length(); i++) {
                char c = token.charAt(i);
                if (c == '*') {
                    body.append(quote(literal.toString()));
                    literal.setLength(0);
                    body.append(slashAware ? ".*" : "[^/]*");
                } else if (c == '?') {
                    body.append(quote(literal.toString()));
                    literal.setLength(0);
                    body.append(slashAware ? "." : "[^/]");
                } else {
                    literal.append(c);
                }
            }
            body.append(quote(literal.toString()));
            return body.toString();
        }

        private static String quote(String literal) {
            return literal.isEmpty() ? "" : Pattern.quote(literal);
        }
    }
}
