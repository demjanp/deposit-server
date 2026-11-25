# Deposit Server release notes

## Unreleased

- Tracked Cypher procedures now generate a UUIDv7 when the final operation ID is omitted or set to `null`. An ordinary call needs only the domain arguments and Deposit token.
- To migrate existing explicit calls, move the token ahead of the operation ID so the call uses `(domainArguments..., token, operationId)`. Commit the registration first, then execute in a separate transaction on the same live Bolt connection. The required-ID controls `deposit.action.begin`, `deposit.get.operation`, and `deposit.action.cancel` retain their argument order. See [tracked operations](doc/procedures.md#tracked-operations).
- Resource pruning is tracked when `dryRun: false`. For a dry run, omit the final operation ID or pass `null`.

## Deposit Server 2.x.x Windows distribution

The Windows x64 distribution includes the Deposit plugin, its runtime dependencies, the Windows launcher, and the documentation and sample collection. It uses separately installed Temurin JDK 21.0.12.1+1 and Neo4j Community 2026.05.0.

- Use `deposit.bat init-password` to initialize the Neo4j administrator password, `deposit.bat shell` to connect with Cypher Shell, and `deposit.bat operation-id` to generate an operation UUID.
- The [installation guide](doc/installation.md) and [quickstart](doc/quickstart.md) cover the complete workflow from the extracted ZIP.
- The server is released under the MIT license. The distribution includes the applicable third-party notices and accompanying source material.

### Build the release ZIP

On Windows x64, open Command Prompt at the root of a clean, full Git checkout at the desired stable release tag. Use a working directory outside the checkout.

```bat
scripts\Build_Release.bat -WorkRoot "C:\DepositBuild"
```

The builder fetches the pinned build inputs, compiles the server plugin, and writes `deposit-server-2.x.x.zip` to the working directory using the version from the Git tag. It skips tests and reuses cached downloads on subsequent builds.
