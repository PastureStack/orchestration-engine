package io.cattle.platform.iaas.api.auth.integration.ldap;

/**
 * Builds LDAP search filters while escaping values according to RFC 4515.
 */
public final class LDAPFilterUtils {

    private LDAPFilterUtils() {
    }

    public static String equality(String attribute, String value) {
        return "(" + attribute + '=' + escapeValue(value) + ")";
    }

    public static String contains(String attribute, String value) {
        return "(" + attribute + "=*" + escapeValue(value) + "*)";
    }

    public static String and(String... filters) {
        StringBuilder builder = new StringBuilder("(&");
        for (String filter : filters) {
            builder.append(filter);
        }
        return builder.append(')').toString();
    }

    public static String escapeValue(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char curChar = value.charAt(i);
            switch (curChar) {
            case '\\':
                builder.append("\\5c");
                break;
            case '*':
                builder.append("\\2a");
                break;
            case '(':
                builder.append("\\28");
                break;
            case ')':
                builder.append("\\29");
                break;
            case '\u0000':
                builder.append("\\00");
                break;
            default:
                builder.append(curChar);
            }
        }
        return builder.toString();
    }
}
