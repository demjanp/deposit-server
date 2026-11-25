# Quickstart

Follow [Installation](installation.md) and start the server first. This quickstart creates a Deposit user, a sample Namespace, and a small `Specimen` and `Context` graph.

## 1. Connect over Bolt

In Command Prompt, from the extracted Deposit directory, run:

```bat
deposit.bat shell
```

The launcher selects the Java and Neo4j paths recorded during setup. Cypher Shell prompts for the Neo4j password. If you configured a different Bolt port, pass the matching address, for example `deposit.bat shell bolt://127.0.0.1:7688`. For a checkout installation, run `scripts\Run_Shell.bat` from the checkout root.

The Neo4j username and password authenticate the Bolt connection. Deposit procedures use a separate Deposit login and token.

Check the plugin version:

```cypher
CALL deposit.get.info() YIELD data RETURN data;
```

## 2. Bootstrap and log in to Deposit

The first Deposit user is an administrator. Bootstrap uses the authenticated Neo4j Bolt connection. Bootstrap before sharing Bolt credentials with other users. Set the parameters below in the interactive session. The shell launcher disables history.

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

In another Command Prompt in the extracted Deposit directory, generate a fresh UUIDv7:

```bat
deposit.bat operation-id
```

For a checkout installation, run `..\test_harness\runs\local-server\distribution\deposit.bat operation-id` from the checkout root. In Cypher Shell, set `$operationId` to the returned value with `:param {operationId: 'paste-the-uuid-here'}`.
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

Set `$namespaceId` to the UUID returned by Namespace creation and run `deposit.bat operation-id` again to get a fresh value for `$operationId`. With Cypher Shell started from the extracted Deposit directory or checkout root, run:

```text
:source doc/sample-data/seed.cypher
```

Keep the same Cypher Shell session with the `token` parameter set. The script uses `deposit.apply.batch` to create the classes, objects, descriptors and relationships in one atomic action. It creates three specimens and one context. Two specimens are related to that context; the third has no Mass value or context relationship.

To repeat the sample, choose a different Namespace name when creating and selecting the Namespace, then source the script again.

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

The first query returns `S-01` with Mass `10.0`, `S-02` with Mass `20.0`, and `S-03` with Mass marked missing in `presence`. The second returns `S-01` and `S-02`. The third returns `2`. See the [query guide](query-language.md) for more examples.

## 6. Convert a native result to portable data

Most entity getters return a Neo4j node or relationship handle. Convert it explicitly before treating it as a data map:

```cypher
CALL deposit.get.class('Specimen', $token) YIELD node
CALL deposit.utils.toData(node, $token) YIELD data
RETURN data;
```

## 7. Stop the server

Enter `:exit` in Cypher Shell. Back in Command Prompt, from the extracted Deposit directory, run:

```bat
deposit.bat stop
```

For a checkout installation, run `scripts\Run_Server.bat -Action Stop` from the checkout root.
