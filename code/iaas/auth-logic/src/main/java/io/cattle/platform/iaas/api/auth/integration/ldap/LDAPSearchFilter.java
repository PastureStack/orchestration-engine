package io.cattle.platform.iaas.api.auth.integration.ldap;

import java.util.ArrayList;
import java.util.List;

/**
 * Parameterized LDAP search filter used by JNDI DirContext.search(..., filterExpr, filterArgs, ...).
 */
public final class LDAPSearchFilter {

    private final String expression;
    private final Object[] arguments;
    private final String escapedExpression;

    private LDAPSearchFilter(String expression, Object[] arguments, String escapedExpression) {
        this.expression = expression;
        this.arguments = arguments;
        this.escapedExpression = escapedExpression;
    }

    public static LDAPSearchFilter equality(String attribute, String value) {
        return new LDAPSearchFilter("(" + attribute + "={0})", new Object[] { value },
                LDAPFilterUtils.equality(attribute, value));
    }

    public static LDAPSearchFilter contains(String attribute, String value) {
        return new LDAPSearchFilter("(" + attribute + "=*{0}*)", new Object[] { value },
                LDAPFilterUtils.contains(attribute, value));
    }

    public static LDAPSearchFilter and(LDAPSearchFilter... filters) {
        StringBuilder expressionBuilder = new StringBuilder("(&");
        StringBuilder escapedBuilder = new StringBuilder("(&");
        List<Object> args = new ArrayList<>();

        for (LDAPSearchFilter filter : filters) {
            expressionBuilder.append(shiftPlaceholders(filter.expression, args.size(), filter.arguments.length));
            escapedBuilder.append(filter.escapedExpression);
            for (Object argument : filter.arguments) {
                args.add(argument);
            }
        }

        return new LDAPSearchFilter(expressionBuilder.append(')').toString(), args.toArray(),
                escapedBuilder.append(')').toString());
    }

    public String expression() {
        return expression;
    }

    public Object[] arguments() {
        return arguments.clone();
    }

    public String escapedExpression() {
        return escapedExpression;
    }

    @Override
    public String toString() {
        return escapedExpression;
    }

    private static String shiftPlaceholders(String expression, int offset, int argumentCount) {
        String shifted = expression;
        for (int i = argumentCount - 1; i >= 0; i--) {
            shifted = shifted.replace("{" + i + "}", "{" + (i + offset) + "}");
        }
        return shifted;
    }
}
