package deposit.service.query;

import deposit.service.query.elements.ParsedQuery;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

public class QueryParser {

    public static ParsedQuery parse(String queryStr) {
        // Set up ANTLR input
        CharStream input = CharStreams.fromString(queryStr);

        // Use the generated lexer and parser
        DepositQueryLexer lexer = new DepositQueryLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DepositQueryParser parser = new DepositQueryParser(tokens);

        // Parse the query and generate the parse tree
        ParseTree tree = parser.query();

        // Walk the parse tree and build the ParsedQuery object
        QueryBuilderVisitor visitor = new QueryBuilderVisitor();
        ParsedQuery parsedQuery = visitor.visit(tree);

        return parsedQuery;
    }
}
