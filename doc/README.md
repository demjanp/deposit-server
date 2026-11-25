# Deposit Server user guide

Deposit Server adds Deposit procedures to Neo4j. Download `deposit-server-2.0.1.zip` from [GitHub Releases](https://github.com/demjanp/deposit-server/releases) for the Windows launcher and sample data. The installation guide also covers the local launcher in a Git checkout.

## Start here

1. [Install and start a server](installation.md).
2. [Connect over Bolt, create a sample collection, and run a query](quickstart.md).
3. Use the [procedure reference](procedures.md) and [query guide](query-language.md).

The quickstart creates a small sample Namespace inside the current Neo4j database. It does not install a separate database dump. Re-run it in a new Namespace to reset the sample.

## Important terms

- **Neo4j login:** the Neo4j account that opens a Bolt connection.
- **Deposit login:** a Deposit account that receives a token for Deposit procedures.
- **Namespace:** a Deposit collection selected for scoped work.
- **Bolt:** the protocol used by Cypher Shell and other Neo4j clients.

The local installation disables Neo4j HTTP and HTTPS. Use Cypher Shell from the Neo4j installation to enter Bolt queries. The Deposit download listener is for resource downloads; it is not a query editor.

The docs cover Deposit procedures registered by the current server. Neo4j's other built-in procedures are outside this reference.
