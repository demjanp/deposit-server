package deposit.service.query;

import deposit.service.query.elements.ParsedQuery;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

public class QueryParser {

	public static ParsedQuery parse(String queryStr) {
		CharStream input = CharStreams.fromString(queryStr);
		DepositQueryLexer lexer = new DepositQueryLexer(input);
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		DepositQueryParser parser = new DepositQueryParser(tokens);
		ParseTree tree = parser.query();
		QueryBuilderVisitor visitor = new QueryBuilderVisitor();
		ParsedQuery parsedQuery = visitor.visit(tree);

		return parsedQuery;
	}
}
