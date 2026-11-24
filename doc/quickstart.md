# Quickstart

This quickstart uses Cypher Shell, the bundled Bolt command-line client. It creates a Deposit user, a sample Namespace, and a small `Specimen` / `Context` graph. See the [Cypher Shell guide](https://neo4j.com/docs/operations-manual/current/cypher-shell/) for command options and interactive commands.

## 1. Connect over Bolt

Run this on the server machine, replacing the Java and Neo4j paths and the Bolt port as needed. Put the release-pinned Java first on `PATH` for Cypher Shell. Neo4j prompts for its password.

```powershell
$env:Path = 'C:\Java\jdk-21\bin;' + $env:Path
& 'C:\Neo4j\bin\cypher-shell.bat' -a bolt://127.0.0.1:7687 -u neo4j -d neo4j --history disable
```

The Neo4j username and password authenticate the Bolt connection. Deposit procedures use a separate Deposit login and token.

Check the plugin version:

```cypher
CALL deposit.get.info() YIELD data RETURN data;
```

## 2. Bootstrap and log in to Deposit

The first Deposit user is an administrator. Bootstrap uses the authenticated Neo4j Bolt connection and does not need a Deposit token. On Neo4j Community, every authenticated native account has administrator privileges, so any Bolt account can claim the first Deposit administrator. Bootstrap before sharing Bolt credentials with other users. Cypher Shell does not prompt for query parameters. Set them in the interactive session; `--history disable` above prevents the commands from being saved to shell history. Do not put credentials or tokens in a file or in the `cypher-shell` command line.

Call bootstrap once, with your chosen Deposit username and password:

```text
:param {username: 'deposit-admin', password: 'choose a strong password'}
```

Then run:

```cypher
CALL deposit.utils.bootstrap($username, $password) YIELD data RETURN data;
```

Log in with the same parameters and keep the returned token for the steps below:

```cypher
CALL deposit.utils.login($username, $password) YIELD value RETURN value AS token;
```

Clear the credential parameters after login:

```text
:param clear
```

In Cypher Shell, set the returned value as a parameter for later commands:

```text
:param {token: 'paste-the-returned-token-here'}
```

Tokens expire and are invalidated by a server restart. Log in again when needed. Bootstrap works once on a fresh installation; later use starts with Deposit login. The Neo4j connection must be authenticated, and the bootstrap procedure requires Neo4j administrative-procedure access.

## 3. Create a sample Namespace

Generate a fresh UUIDv7 using a local UUIDv7 generator. For example, run
`py -3.14 -c "import uuid; print(uuid.uuid7())"` from PowerShell. Set
`$operationId` to that value with `:param {operationId: 'paste-the-uuid-here'}`.
In the same Cypher Shell session, run each statement as a separate transaction:

```cypher
CALL deposit.action.begin($operationId, 'server', 'deposit.create.namespace',
  ['Deposit sample', {historyMode: 'disabled'}], $token) YIELD data RETURN data;

CALL deposit.create.namespace('Deposit sample', {historyMode: 'disabled'}, $operationId, $token) YIELD node
RETURN node.id AS namespaceId, node.name AS namespace;

CALL deposit.set.activeNamespace('Deposit sample', $token) YIELD node
RETURN node.name AS activeNamespace;
```

The Namespace is the sample collection. The user who creates it becomes its owner.

## 4. Load the sample graph

Set `$namespaceId` to the UUID returned by Namespace creation and replace
`$operationId` with a fresh UUIDv7. From the repository root, run the seed
script in the same Cypher Shell session:

```text
:source doc/sample-data/seed.cypher
```

Keep the same Cypher Shell session with the `token` parameter set. The script uses `deposit.apply.batch` to create the classes, objects, descriptors and relationships in one atomic action. It creates three specimens and one context. Two specimens are related to that context; the third has no Mass value or context relationship.

To reset the sample, create and select a new Namespace, then source the script again. The script does not delete data.

## 5. Run queries

List specimens and masses:

```cypher
CALL deposit.get.query('SELECT Specimen.Code AS code, Specimen.Mass AS mass ORDER BY code', {}, {}, $token)
YIELD data, columns, presence
RETURN data, columns, presence;
```

Find specimens with a context relationship:

```cypher
CALL deposit.get.query('SELECT Specimen.Code AS code WHERE Specimen-foundIn->Context ORDER BY code', {}, {}, $token)
YIELD data RETURN data;
```

Count the connected specimens:

```cypher
CALL deposit.get.query('SELECT COUNT(Specimen) AS specimens WHERE Specimen-foundIn->Context', {}, {}, $token)
YIELD data RETURN data;
```

Expected results are `S-01`, `S-02`, `S-03` in the first query; `S-01`, `S-02` in the second; and `2` in the third. The first query also has a missing Mass cell for `S-03`. Query stream rows have data and column/presence metadata. See the [query guide](query-language.md) for more examples.

## 6. Convert a native result to portable data

Most entity getters return a Neo4j node or relationship handle. Convert it explicitly before treating it as a data map:

```cypher
CALL deposit.get.class('Specimen', $token) YIELD node
CALL deposit.utils.toData(node, $token) YIELD data
RETURN data;
```

## Graphical client

The supported local setup uses Cypher Shell. A Neo4j Browser client is optional and must be configured as a separate client that connects over Bolt. Browser connection behavior can depend on its delivery mode and TLS scheme; verify it with the installed release before relying on it. Do not enable Neo4j HTTP just to expose a query page.
