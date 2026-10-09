package deposit.service.query;

import deposit.service.query.elements.*;
import deposit.service.query.translators.*;

import java.util.*;

public class QueryTranslator {

	public static QueryTranslatorResult translateToCypher(String query) {
		ParsedQuery parsedQuery = QueryParser.parse(query);
		StringBuilder cypherQuery = new StringBuilder();
		Set<String> objectVariables = new HashSet<>();
		Set<String> descriptorVariables = new HashSet<>();
		Set<String> relVariables = new HashSet<>();

		String matchClause = MatchClauseBuilder.buildMatchClause(parsedQuery, objectVariables, descriptorVariables, relVariables);

		cypherQuery.append(matchClause);

		String whereClause = WhereClauseBuilder.buildWhereClause(parsedQuery);
		if (!whereClause.isEmpty()) {
			appendWithClause(cypherQuery, objectVariables, descriptorVariables, relVariables);
			cypherQuery.append("\nWHERE ").append(whereClause).append("\n");
		}

		ReturnClauseBuilder.ReturnResult result = ReturnClauseBuilder.buildReturnClause(parsedQuery);
		String returnClause = result.getReturnClause();
		Map<String, String> returnedKeys = result.getReturnedKeys();
		cypherQuery.append("RETURN ").append(returnClause);

		String orderByClause = OrderByClauseBuilder.buildOrderByClause(parsedQuery);
		if (!orderByClause.isEmpty()) {
			cypherQuery.append("\n").append("ORDER BY ").append(orderByClause);
		}

		return new QueryTranslatorResult(cypherQuery.toString(), returnedKeys);
	}
	
	private static void appendWithClause(StringBuilder matchClause, Set<String> objectVariables, Set<String> descriptorVariables, Set<String> relVariables) {
		Set<String> matchedVariables = new LinkedHashSet<>(objectVariables);
		matchedVariables.addAll(descriptorVariables);
		matchedVariables.addAll(relVariables);
		matchClause.append("WITH ").append(String.join(", ", matchedVariables));
	}

	public static class QueryTranslatorResult {
		private final String cypherQuery;
		private final Map<String, String> returnedKeys;

		public QueryTranslatorResult(String cypherQuery, Map<String, String> returnedKeys) {
			this.cypherQuery = cypherQuery;
			this.returnedKeys = returnedKeys;
		}

		public String getCypherQuery() {
			return cypherQuery;
		}
		
		public Map<String, String> getReturnedKeys() {
			return returnedKeys;
		}
	}
}
