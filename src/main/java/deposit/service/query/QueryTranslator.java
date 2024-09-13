package deposit.service.query;

import deposit.service.query.elements.*;
import deposit.service.query.translators.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
			cypherQuery.append("WHERE ").append(whereClause).append("\n");
		}

		ReturnClauseBuilder.ReturnResult result = ReturnClauseBuilder.buildReturnClause(parsedQuery);
		String returnClause = result.getReturnClause();
		List<String> returnedNodes = result.getReturnedNodes();
		List<String> returnedKeys = result.getReturnedKeys();
		cypherQuery.append("RETURN ").append(returnClause);

		String orderByClause = OrderByClauseBuilder.buildOrderByClause(parsedQuery);
		if (!orderByClause.isEmpty()) {
			cypherQuery.append("\n").append("ORDER BY ").append(orderByClause);
		}

		return new QueryTranslatorResult(cypherQuery.toString(), returnedNodes, returnedKeys);
	}

	public static class QueryTranslatorResult {
		private final String cypherQuery;
		private final List<String> returnedNodes;
		private final List<String> returnedKeys;

		public QueryTranslatorResult(String cypherQuery, List<String> returnedNodes, List<String> returnedKeys) {
			this.cypherQuery = cypherQuery;
			this.returnedNodes = returnedNodes;
			this.returnedKeys = returnedKeys;
		}

		public String getCypherQuery() {
			return cypherQuery;
		}

		public List<String> getReturnedNodes() {
			return returnedNodes;
		}

		public List<String> getReturnedKeys() {
			return returnedKeys;
		}
	}
}
