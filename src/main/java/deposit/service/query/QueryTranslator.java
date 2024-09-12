package deposit.service.query;

import deposit.service.query.elements.*;
import deposit.service.query.translators.*;

import java.util.HashSet;
import java.util.Set;

public class QueryTranslator {

	public static String translateToCypher(String query) {
		ParsedQuery parsedQuery = QueryParser.parse(query);
		StringBuilder cypherQuery = new StringBuilder();
		Set<String> objectVariables = new HashSet<>();
		Set<String> descriptorVariables = new HashSet<>();
		Set<String> relVariables = new HashSet<>();

		String matchClause = MatchClauseBuilder.buildMatchClause(parsedQuery, objectVariables, descriptorVariables);
		String optionalMatchClause = OptionalMatchClauseBuilder.buildOptionalMatchClause(parsedQuery, objectVariables, relVariables);

		appendMatchClause(cypherQuery, matchClause);

		// Track all matched variables
		Set<String> matchedVariables = new HashSet<>(objectVariables);
		matchedVariables.addAll(descriptorVariables);

		if (!matchedVariables.isEmpty() && !optionalMatchClause.isEmpty()) {
			appendWithClause(cypherQuery, matchedVariables);
		}

		appendOptionalMatchClause(cypherQuery, optionalMatchClause);

		matchedVariables.addAll(relVariables);
		if (!matchedVariables.isEmpty()) {
			appendWithClause(cypherQuery, matchedVariables);
		}

		String whereClause = WhereClauseBuilder.buildWhereClause(parsedQuery);
		appendWhereClause(cypherQuery, whereClause);

		String returnClause = ReturnClauseBuilder.buildReturnClause(parsedQuery);
		appendReturnClause(cypherQuery, returnClause);

		String orderByClause = OrderByClauseBuilder.buildOrderByClause(parsedQuery);
		appendOrderByClause(cypherQuery, orderByClause);

		return cypherQuery.toString();
	}

	private static void appendMatchClause(StringBuilder cypherQuery, String matchClause) {
		if (!matchClause.isEmpty()) {
			cypherQuery.append(matchClause).append("\n");
		}
	}

	private static void appendWithClause(StringBuilder cypherQuery, Set<String> matchedVariables) {
		cypherQuery.append("WITH ").append(String.join(", ", matchedVariables)).append("\n");
	}

	private static void appendOptionalMatchClause(StringBuilder cypherQuery, String optionalMatchClause) {
		if (!optionalMatchClause.isEmpty()) {
			cypherQuery.append(optionalMatchClause).append("\n");
		}
	}

	private static void appendWhereClause(StringBuilder cypherQuery, String whereClause) {
		if (!whereClause.isEmpty()) {
			cypherQuery.append("WHERE ").append(whereClause).append("\n");
		}
	}

	private static void appendReturnClause(StringBuilder cypherQuery, String returnClause) {
		if (!returnClause.isEmpty()) {
			cypherQuery.append("RETURN ").append(returnClause);
		}
	}

	private static void appendOrderByClause(StringBuilder cypherQuery, String orderByClause) {
		if (!orderByClause.isEmpty()) {
			cypherQuery.append("\n").append("ORDER BY ").append(orderByClause);
		}
	}
}
