package info.trizub.clamav.webclient.identity;

import org.springframework.ldap.support.LdapEncoder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates operator-provided templates and escapes values, never the filter operators. */
final class LdapGroupSearchFilter {
    private static final Pattern PARAMETER = Pattern.compile("\\{([^{}]*)}");
    private final String template;

    LdapGroupSearchFilter(IdentityProperties.Ldap config) {
        template = validate(config.getGroupSearchFilter(), config.getGroupMemberAttribute(), config.isNestedGroups());
    }

    static String validate(String configured, String memberAttribute, boolean nestedGroups) {
        String filter = configured == null ? "" : configured.trim();
        if (filter.isEmpty()) {
            if (memberAttribute == null || !memberAttribute.matches("[A-Za-z][A-Za-z0-9-]{0,63}"))
                throw new IllegalArgumentException("Unsafe LDAP group member attribute");
            filter = "(" + memberAttribute + "={0})";
        }
        if (filter.length() > 1024 || filter.chars().anyMatch(c -> Character.isISOControl(c)))
            throw new IllegalArgumentException("Invalid LDAP group search filter");
        Matcher parameters = PARAMETER.matcher(filter);
        boolean hasDn = false, hasUsername = false;
        while (parameters.find()) {
            switch (parameters.group(1)) {
                case "0" -> hasDn = true;
                case "1" -> hasUsername = true;
                default -> throw new IllegalArgumentException("LDAP group search filter only supports {0} and {1}");
            }
        }
        String remaining = parameters.replaceAll("value");
        if (remaining.indexOf('{') >= 0 || remaining.indexOf('}') >= 0 || (!hasDn && !hasUsername))
            throw new IllegalArgumentException("LDAP group search filter must include {0} (full DN) or {1} (login name)");
        if (nestedGroups && !hasDn)
            throw new IllegalArgumentException("Nested LDAP groups require {0} in the group search filter");
        // Reject malformed structure locally; the directory validates its supported attributes/matching rules.
        Parser parser = new Parser(remaining);
        parser.filter(0);
        if (parser.position != remaining.length()) throw invalid();
        return filter;
    }

    String render(String fullDn, String username) {
        String[] values = {LdapEncoder.filterEncode(fullDn), LdapEncoder.filterEncode(username)};
        Matcher parameters = PARAMETER.matcher(template);
        StringBuilder result = new StringBuilder();
        // Single-pass substitution: braces occurring in a DN/username must never become parameters.
        while (parameters.find())
            parameters.appendReplacement(result, Matcher.quoteReplacement(values[Integer.parseInt(parameters.group(1))]));
        parameters.appendTail(result);
        return result.toString();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid LDAP group search filter structure");
    }

    private static final class Parser {
        private final String value;
        private int position;
        private Parser(String value) { this.value = value; }

        private void filter(int depth) {
            if (depth > 64) throw invalid();
            expect('(');
            char operator = current();
            if (operator == '&' || operator == '|') {
                position++;
                int count = 0;
                while (current() == '(') { filter(depth + 1); count++; }
                if (count == 0) throw invalid();
            } else if (operator == '!') {
                position++;
                filter(depth + 1);
            } else {
                int start = position;
                boolean equality = false;
                while (current() != ')') {
                    char c = current();
                    if (c == '\0' || c == '(') throw invalid();
                    if (c == '\\') {
                        position++;
                        for (int i = 0; i < 2; i++) {
                            if (Character.digit(current(), 16) < 0) throw invalid();
                            position++;
                        }
                        continue;
                    }
                    if (c == '=' && !equality) {
                        String attribute = value.substring(start, position);
                        if (!attribute.matches("[A-Za-z0-9;:.-]+[~<>]?")) throw invalid();
                        equality = true;
                    }
                    position++;
                }
                if (!equality) throw invalid();
            }
            expect(')');
        }

        private char current() { return position < value.length() ? value.charAt(position) : '\0'; }
        private void expect(char expected) {
            if (current() != expected) throw invalid();
            position++;
        }
    }
}
