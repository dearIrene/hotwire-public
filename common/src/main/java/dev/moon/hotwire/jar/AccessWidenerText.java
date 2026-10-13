package dev.moon.hotwire.jar;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public final class AccessWidenerText {
    public record Clause(int line, String kind, String owner, String text) {
    }

    private static final int AW_V1 = 1;
    private static final int AW_V2 = 2;
    private static final int CT_V1 = 3;
    private static final int CT_V2 = 4;
    private static final Pattern V1_DELIMITER = Pattern.compile("\\s+");
    private static final Pattern V2_DELIMITER = Pattern.compile("[ \\t]+");
    private static final String TRANSITIVE = "transitive-";

    private final String path;
    private final byte[] bytes;
    private final List<Clause> clauses;

    private AccessWidenerText(String path, byte[] bytes, List<Clause> clauses) {
        this.path = path;
        this.bytes = bytes.clone();
        this.clauses = List.copyOf(clauses);
    }

    public String getPath() {
        return this.path;
    }

    public byte[] getBytes() {
        return this.bytes.clone();
    }

    public List<Clause> getClauses() {
        return this.clauses;
    }

    static AccessWidenerText parse(String path, byte[] bytes) {
        List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
        if (lines.isEmpty()) {
            throw new IllegalArgumentException(path + " is empty; expected a header 'accessWidener v2 <namespace>'");
        }
        int version = parseVersion(path, lines.get(0));
        Pattern delimiter = version < AW_V2 ? V1_DELIMITER : V2_DELIMITER;
        List<Clause> clauses = new ArrayList<>();
        for (int number = 2; number <= lines.size(); number++) {
            String line = stripComment(version, lines.get(number - 1));
            if (line.isEmpty()) {
                continue;
            }
            if (Character.isWhitespace(line.codePointAt(0))) {
                throw makeError(path, number, "leading whitespace is not allowed");
            }
            clauses.add(parseClause(path, number, version, List.of(delimiter.split(line, 0)), line));
        }
        return new AccessWidenerText(path, bytes, clauses);
    }

    private static int parseVersion(String path, String header) {
        String[] parts = V1_DELIMITER.split(header, 0);
        if (parts.length != 3) {
            throw makeError(path, 1, "expected a header 'accessWidener <version> <namespace>', got '" + header + "'");
        }
        return switch (parts[0] + " " + parts[1]) {
            case "accessWidener v1" -> AW_V1;
            case "accessWidener v2" -> AW_V2;
            case "classTweaker v1" -> CT_V1;
            case "classTweaker v2" -> CT_V2;
            default -> throw makeError(path, 1, "unsupported format '" + parts[0] + " " + parts[1] + "'");
        };
    }

    private static String stripComment(int version, String line) {
        int comment = line.indexOf('#');
        if (comment < 0) {
            return line;
        }
        String retained = line.substring(0, comment);
        return version <= AW_V1 ? retained.trim() : retained;
    }

    private static Clause parseClause(String path, int number, int version, List<String> tokens, String text) {
        String first = tokens.get(0);
        boolean transitive = version >= AW_V2 && first.startsWith(TRANSITIVE);
        String kind = transitive ? first.substring(TRANSITIVE.length()) : first;
        if (version >= CT_V1 && kind.equals("inject-interface")) {
            return parseClassClause(path, number, tokens, kind, text, "inject-interface <class> <interface>");
        }
        if (version >= CT_V2 && kind.equals("extend-enum")) {
            return parseClassClause(path, number, tokens, kind, text, "extend-enum <class> <constant>");
        }
        return parseAccessClause(path, number, tokens, kind, text);
    }

    private static Clause parseClassClause(String path, int number, List<String> tokens, String kind, String text,
            String shape) {
        if (tokens.size() != 3) {
            throw makeError(path, number, "expected '" + shape + "', got '" + text + "'");
        }
        return new Clause(number, kind, parseOwner(path, number, tokens.get(1)), text);
    }

    private static Clause parseAccessClause(String path, int number, List<String> tokens, String access, String text) {
        switch (access.toLowerCase(Locale.ROOT)) {
            case "accessible", "extendable", "mutable" -> {
            }
            default -> throw makeError(path, number, "unknown access type '" + access + "'");
        }
        if (tokens.size() < 2) {
            throw makeError(path, number, "expected class, field or method after '" + tokens.get(0) + "'");
        }
        String target = tokens.get(1);
        int expected = switch (target) {
            case "class" -> 3;
            case "field", "method" -> 5;
            default -> throw makeError(path, number, "unsupported type '" + target + "'");
        };
        if (tokens.size() != expected) {
            throw makeError(path, number, "expected " + expected + " words for '" + access + " " + target + "', got '"
                    + text + "'");
        }
        return new Clause(number, access + " " + target, parseOwner(path, number, tokens.get(2)), text);
    }

    private static String parseOwner(String path, int number, String name) {
        if (name.contains(".")) {
            throw makeError(path, number, "class names are written a/b/C, not a.b.C: '" + name + "'");
        }
        return name;
    }

    private static IllegalArgumentException makeError(String path, int line, String detail) {
        return new IllegalArgumentException(path + " line " + line + ": " + detail);
    }

    public List<Clause> getClausesOnLoadedClasses(Predicate<String> isLoaded) {
        List<Clause> loaded = new ArrayList<>();
        for (Clause clause : this.clauses) {
            if (isLoaded.test(clause.owner())) {
                loaded.add(clause);
            }
        }
        return loaded;
    }

    public byte[] getBytesWithout(Predicate<String> isLoaded) {
        List<String> lines = new ArrayList<>(new String(this.bytes, StandardCharsets.UTF_8).lines().toList());
        for (Clause clause : this.getClausesOnLoadedClasses(isLoaded)) {
            if (clause.kind().startsWith("accessible ") || clause.kind().startsWith("extendable ")
                    || clause.kind().startsWith("mutable ")) {
                lines.set(clause.line() - 1, "# " + lines.get(clause.line() - 1));
            }
        }
        return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    List<String> getOwners() {
        List<String> owners = new ArrayList<>();
        for (Clause clause : this.clauses) {
            if (!owners.contains(clause.owner())) {
                owners.add(clause.owner());
            }
        }
        return owners;
    }

    public static String describeRefusal(String modName, Clause clause) {
        return modName + " opens " + clause.owner().replace('/', '.') + " (" + clause.text() + " in "
                + "its access widener), which the game has loaded already, and a loaded class's access cannot change.";
    }
}
