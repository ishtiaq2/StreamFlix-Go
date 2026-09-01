package com.graphvault.titles.datafetcher;

import com.graphvault.titles.repository.TitleRepository;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;

import java.util.List;

/**
 * This is the entire "backend" of titles-dgs, as far as the GraphQL contract is concerned.
 *
 * {@code @DgsComponent} marks this class as something DGS should scan for data-fetching
 * methods, the same way {@code @RestController} marks a class for HTTP routing. Each
 * {@code @DgsQuery}-annotated method here corresponds to ONE field under `type Query` in
 * schema.graphqls — the method name ("titles", "title") has to match the schema field name
 * exactly, which is how DGS knows which method answers which query without any extra config.
 *
 * Compare this to a REST controller: there, you'd write one endpoint per URL shape you expect
 * clients to want (GET /titles, GET /titles/{id}, maybe GET /titles/{id}/summary...). Here, a
 * single `title(id: ID!): Title` field lets any caller request exactly the subset of Title's
 * fields they need, in one round trip, with no extra endpoints — this is the flexibility the
 * blog credits GraphQL for on the client-facing side of Netflix's architecture.
 */
@DgsComponent
public class TitleDataFetcher {

    private final TitleRepository titleRepository;

    public TitleDataFetcher(TitleRepository titleRepository) {
        this.titleRepository = titleRepository;
    }

    @DgsQuery
    public List<TitleType> titles() {
        return titleRepository.findAll().stream().map(TitleType::from).toList();
    }

    @DgsQuery
    public TitleType title(@InputArgument Long id) {
        // @InputArgument binds the GraphQL argument named "id" in `title(id: ID!)` to this
        // parameter by name (Long here, even though the schema type is ID -- DGS coerces the
        // incoming string-shaped ID argument to whatever Java type the method declares).
        return titleRepository.findById(id).map(TitleType::from).orElse(null);
    }
}
