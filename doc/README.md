# Deposit Server user guide

This guide covers Deposit Server 2.x.x. Its examples use the optional operation ID signatures in the current checkout. For a packaged release, use the guide included in the archive to match the installed plugin. [GitHub Releases](https://github.com/demjanp/deposit-server/releases)

## Start here

1. [Install and start a server](installation.md).
2. [Connect over Bolt, create a sample collection, and run a query](quickstart.md).
3. Use the [procedure reference](procedures.md) and [query guide](query-language.md).

- [Load and preview CSV, XLSX, or GeoPackage data](procedures.md#load), then [map and import it](procedures.md#import).
- [Define a Pattern](procedures.md#depositcreatepattern) to match Objects or import form data.
- [Transfer a package between servers](procedures.md#transfer-a-zip-and-import-into-an-existing-namespace).
- [Download attachments from an exported table](procedures.md#download-attachments-in-a-browser).

The quickstart creates a small sample Namespace inside the current Neo4j database. It does not install a separate database dump. Re-run it in a new Namespace to reset the sample.

## Important terms

- **Neo4j login:** the Neo4j account that opens a Bolt connection.
- **Deposit login:** a Deposit account that receives a token for Deposit procedures.
- **Namespace:** a Deposit collection selected for scoped work.
- **Bolt:** the protocol used by Cypher Shell and other Neo4j clients.

The local installation disables Neo4j HTTP and HTTPS. Use Cypher Shell from the Neo4j installation to enter Bolt queries. The Deposit download listener is for resource downloads; it is not a query editor.

The docs cover Deposit procedures registered by the current server. Neo4j's other built-in procedures are outside this reference.
