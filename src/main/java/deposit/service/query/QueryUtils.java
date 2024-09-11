package deposit.service.query;

import java.util.Set;

public class QueryUtils {

	// Set of Cypher reserved keywords (simplified example)
	private static final Set<String> RESERVED_KEYWORDS = Set.of(
			"MATCH", "RETURN", "CREATE", "DELETE", "SET", "WHERE", "AND", "OR", "NOT", "WITH"
	);

	// Utility method to remove surrounding double quotes from a string, if present
	public static String stripQuotes(String name) {
		if (name.startsWith("\"") && name.endsWith("\"")) {
			return name.substring(1, name.length() - 1);  // Remove quotes
		}
		return name;
	}

	public static String sanitizeAlias(String alias) {
		alias = alias.replaceAll("[^a-zA-Z0-9_]", "_");
		if (RESERVED_KEYWORDS.contains(alias.toUpperCase())) {
			alias = alias + "_alias";
		}
		return alias;
	}

	public static String generateAliasForClass(String className) {
        return QueryUtils.sanitizeAlias(QueryUtils.stripQuotes(className));
    }

    public static String generateAliasForDescriptor(String className, String descriptor) {
        return QueryUtils.sanitizeAlias(QueryUtils.stripQuotes(className)) + "_" + QueryUtils.sanitizeAlias(QueryUtils.stripQuotes(descriptor));
    }
}
