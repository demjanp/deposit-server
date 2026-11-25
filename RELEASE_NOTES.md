# Deposit Server release notes

## Deposit Server 2.x.x Windows distribution

The Windows x64 distribution includes the Deposit plugin, its runtime dependencies, the Windows launcher, and the documentation and sample collection. It uses separately installed Temurin JDK 21.0.12.1+1 and Neo4j Community 2026.05.0.

- Use `deposit.bat init-password` to initialize the Neo4j administrator password, `deposit.bat shell` to connect with Cypher Shell, and `deposit.bat operation-id` to generate an operation UUID.
- The [installation guide](doc/installation.md) and [quickstart](doc/quickstart.md) cover the complete workflow from the extracted ZIP.
- The server is released under the MIT license. The distribution includes the applicable third-party notices and accompanying source material.
