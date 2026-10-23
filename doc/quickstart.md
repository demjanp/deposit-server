# Quickstart

This quickstart uses Cypher Shell, the bundled Bolt command-line client. It creates a Deposit user, a sample Namespace, and a small `Specimen` / `Context` graph. See the [Cypher Shell guide](https://neo4j.com/docs/operations-manual/current/cypher-shell/) for command options and interactive commands.

## 1. Connect over Bolt

Run this on the server machine, replacing the path and port as needed. Neo4j prompts for its password:

```powershell
& 'C:\Neo4j\bin\cypher-shell.bat' -a bolt://127.0.0.1:7687 -u neo4j -d neo4j --history disable
```

The Neo4j username and password authenticate the Bolt connection. Deposit procedures use a separate Deposit login and token.

Check the plugin version:

```cypher
CALL deposit.get.info() YIELD data RETURN data;
```

## 2. Bootstrap and log in to Deposit

The first Deposit user is an administrator. Read the bootstrap secret from the protected `deposit-bootstrap.secret` file under the configured `secretRoot`. Cypher Shell does not prompt for query parameters. Set them in the interactive session; `--history disable` above prevents the commands from being saved to shell history. Do not put credentials or tokens in a file or in the `cypher-shell` command line.

Call bootstrap once, with your chosen Deposit username and password:

```text
:param {bootstrapSecret: 'paste secret from protected file', username: 'deposit-admin', password: 'choose a strong password'}
```

Then run:

```cypher
CALL deposit.utils.bootstrap($bootstrapSecret, $username, $password) YIELD data RETURN data;
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

Tokens expire and are invalidated by a server restart. Log in again when needed. A new installation has a one-time bootstrap secret; normal use starts with Deposit login.

## 3. Create a sample Namespace

Create a new Namespace, then select it in a separate command:

```cypher
CALL deposit.create.namespace('Deposit sample', {historyMode: 'disabled'}, $token) YIELD node
RETURN node.name AS namespace;

CALL deposit.set.activeNamespace('Deposit sample', $token) YIELD node
RETURN node.name AS activeNamespace;
```

The Namespace is the sample collection. The user who creates it becomes its owner.

## 4. Load the sample graph

From the repository root, in a fresh Namespace, run the seed script:

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
