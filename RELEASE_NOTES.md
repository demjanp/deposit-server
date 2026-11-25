# Deposit Server 2.0.1

The Windows x64 distribution includes the Deposit plugin, its runtime dependencies, the Windows launcher, and the documentation and sample collection. It uses separately installed Temurin JDK 21.0.12.1+1 and Neo4j Community 2026.05.0.

- Use `deposit.bat init-password` to initialize the Neo4j administrator password, `deposit.bat shell` to connect with Cypher Shell, and `deposit.bat operation-id` to generate an operation UUID.
- The [installation guide](doc/installation.md) and [quickstart](doc/quickstart.md) cover the complete workflow from the extracted ZIP.
- The server is released under the MIT license. The distribution includes the applicable third-party notices and accompanying source material.

## Build the release ZIP

On Windows x64, open Command Prompt at the root of a clean, full Git checkout at tag `2.0.1`. Use a working directory outside the checkout:

```bat
scripts\Build_Release.bat -WorkRoot "C:\DepositBuild\2.0.1"
```

The builder fetches the pinned build inputs, compiles the server plugin, and writes `deposit-server-2.0.1.zip` to the working directory. It skips tests and reuses cached downloads on subsequent builds.
