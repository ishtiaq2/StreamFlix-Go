package com.graphvault.gateway.planner;

import com.graphvault.gateway.config.GatewayProperties;
import graphql.language.*;
import graphql.parser.Parser;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * The closest thing this gateway has to a real federation query planner — except a real one
 * (Apollo Router, DGS's federated gateway) understands the FULL composed schema and can plan
 * queries that reach into a single type's fields across multiple subgraphs (e.g. `Title.artwork`
 * physically living in a different service than `Title.name`). This one only understands
 * DISTINCT TOP-LEVEL FIELDS, each owned entirely by one subgraph — see
 * docs/ARCHITECTURE_VS_BLOG.md for exactly where that line is and why we drew it there.
 *
 * The technique itself is real, though: parse the incoming query into graphql-java's AST
 * (the same parser DGS and every graphql-java-based server use internally), group the
 * top-level selections by owner, and re-print each group as its own small, valid GraphQL
 * document to forward downstream.
 */
@Component
public class QueryPlanner {

    private final GatewayProperties properties;
    private final Parser parser = new Parser();

    public QueryPlanner(GatewayProperties properties) {
        this.properties = properties;
    }

    public List<SubgraphPlan> plan(String rawQuery) {
        Document document = parser.parseDocument(rawQuery);

        OperationDefinition operation = document.getDefinitions().stream()
                .filter(OperationDefinition.class::isInstance)
                .map(OperationDefinition.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No operation found in the submitted query"));

        // Group each top-level field by which subgraph owns it. We only handle plain Field
        // selections here -- fragments (`...on Type`) and inline fragments aren't supported by
        // this simplified planner, which is a real limitation worth knowing about before you
        // try to paste an arbitrarily complex query from a bigger project into this gateway.
        Map<String, List<Selection>> selectionsBySubgraph = new LinkedHashMap<>();
        for (Selection<?> selection : operation.getSelectionSet().getSelections()) {
            if (!(selection instanceof Field field)) {
                throw new IllegalArgumentException(
                        "This teaching gateway only supports plain top-level fields, not fragments: " + selection);
            }
            String subgraph = properties.fieldOwners().get(field.getName());
            if (subgraph == null) {
                throw new IllegalArgumentException(
                        "No subgraph is configured to own field \"" + field.getName() + "\" "
                                + "(check graphvault.field-owners in application.yml)");
            }
            selectionsBySubgraph.computeIfAbsent(subgraph, k -> new ArrayList<>()).add(field);
        }

        List<SubgraphPlan> plans = new ArrayList<>();
        for (Map.Entry<String, List<Selection>> entry : selectionsBySubgraph.entrySet()) {
            String subgraphName = entry.getKey();
            String url = properties.subgraphs().get(subgraphName);
            if (url == null) {
                throw new IllegalStateException("No URL configured for subgraph \"" + subgraphName + "\"");
            }

            SelectionSet subSelectionSet = SelectionSet.newSelectionSet()
                    .selections(entry.getValue())
                    .build();
            OperationDefinition subOperation = OperationDefinition.newOperationDefinition()
                    .operation(OperationDefinition.Operation.QUERY)
                    .selectionSet(subSelectionSet)
                    .build();
            Document subDocument = Document.newDocument().definition(subOperation).build();

            // AstPrinter turns the AST fragment we just built back into valid GraphQL query
            // text -- the exact inverse of what Parser.parseDocument() did to the original
            // query. The subgraph on the other end has no idea this text didn't come straight
            // from a browser.
            String queryText = AstPrinter.printAst(subDocument);

            plans.add(new SubgraphPlan(subgraphName, url, queryText));
        }
        return plans;
    }
}
