# Query guide

`deposit.get.query` accepts Deposit Query Language text, a parameter map, options and a Deposit token. It is not Cypher. Use Cypher Shell to call the procedure over Bolt.

```cypher
CALL deposit.get.query(
  'SELECT Specimen.Code AS code WHERE Specimen.Mass >= $minimum ORDER BY code',
  {minimum: 10.0},
  {},
  $token
) YIELD data, columns, presence
RETURN data, columns, presence;
```

The sample graph in the [quickstart](quickstart.md) provides `Specimen.Code`, `Specimen.Mass` and `Context.Code`.

## Select fields

```text
SELECT Specimen.Code
SELECT Specimen.Code AS code, Specimen.Mass AS mass
SELECT Specimen AS specimen
```

A Class name selects matching Objects. A `Class.Descriptor` selects that value. Use `AS` to give a result a short name.

## Filter values

```text
SELECT Specimen.Code WHERE Specimen.Mass > 10
SELECT Specimen.Code WHERE Specimen.Code IN ['S-01', 'S-03']
SELECT Specimen.Code WHERE Specimen.Mass IS MISSING
SELECT Specimen.Code WHERE Specimen.Mass IS NULL AND Specimen.Mass IS NOT MISSING
```

Use `$name` parameters and provide their values in the second procedure argument. Parameters keep values separate from query text.

`IS NULL` matches both a missing Descriptor and an explicit null value. Add `IS NOT MISSING` to select only explicit nulls. Query results include `presence` metadata so clients can distinguish a present null from a missing value.

## Match relationships

```text
SELECT Specimen.Code WHERE Specimen-foundIn->Context
SELECT Specimen.Code WHERE NOT Specimen-foundIn->Context
SELECT Specimen.Code, Context.Code WHERE Specimen-foundIn->Context
```

An arrow matches outgoing relationships. `<-label-` matches incoming, and `-label-` matches either direction. Without a label, a relationship of any label matches. A relationship-only endpoint is an existential condition; `NOT Specimen-foundIn->Context` means no related Context exists.

For bounded paths, give both limits:

```text
SELECT Specimen.Code WHERE Specimen-*1..3->Context
```

The maximum path length is 10. Unbounded `*` is not supported.

## Aggregate and sort

```text
SELECT COUNT(Specimen) AS count
SELECT AVG(Specimen.Mass) AS mean, MAX(Specimen.Mass) AS largest
SELECT Context.Code AS context, COUNT(Specimen) AS specimens WHERE Specimen-foundIn->Context
SELECT Specimen.Code AS code ORDER BY code DESC
```

Supported aggregates are `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`, `STDEV` and `PERCENTILE`. Aggregate results need aliases. `COLLECT` is a separate value stream, for example `SELECT COLLECT(Specimen.Mass) AS mass`.

## Names and binding

Quote Class or Descriptor names containing spaces or special characters with double quotes. For example: `SELECT "Sample set"."Dry mass" AS mass`.

To select two Objects of the same Class, use aliases:

```text
SELECT Specimen AS a, Specimen AS b WHERE a-relatedTo->b
```

Selected Objects must be connected by explicit relationship predicates or the query's automatic same-Namespace connectivity rule. Disconnected bindings are rejected to prevent accidental Cartesian results.

## Large results

`deposit.get.query` streams results. Use `deposit.get.queryPage` for cursor paging. A page request contains the query, parameters, options and optional page size/cursor. The default page size is 200; the maximum is 1,000. Cursors bind to the actor, Namespace, query and revision. After a revision conflict, refresh and start a new page request.

See the [procedure reference](procedures.md) for exact procedure signatures and call templates.
