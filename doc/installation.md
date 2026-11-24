# Installation and configuration

## Supported runtime

The current Windows release uses Neo4j Community 2026.05.0, Temurin Java 21 and Cypher 25. Use the exact versions and checksums in the release's `artifacts.json` and `release-manifest.json`. Java and Neo4j are separate from the Deposit plugin package.

The steps below cover initial password setup, launcher commands and service configuration.

## Install a release package

1. Install the release-pinned Java and Neo4j versions in stable directories.
2. Extract the Deposit release package to a stable directory.
3. Set the Neo4j administrator password with Neo4j's `neo4j-admin` utility before the first start. Store the password in a protected credential store.
4. From PowerShell, run the package launcher:

   ```powershell
   .\deposit.bat setup C:\Neo4j C:\Java\jdk-21 C:\Deposit\resources C:\Deposit\exports 7687
   .\deposit.bat start
   .\deposit.bat status
   ```

   Replace the example paths with the actual Neo4j, Java, resource and export paths. The final number is the Bolt port; it defaults to `7687`.
5. Open a Bolt client on the server machine using `bolt://127.0.0.1:7687`.

The launcher installs the plugin and its dependencies, enables authentication, configures resource storage and a loopback download listener, and protects `secretRoot`. Deposit uses this directory for persistent cursor and shutdown-control keys. Create the first Deposit administrator over an authenticated Bolt connection. The launcher disables Neo4j HTTP/HTTPS. It does not change firewall rules or install a Windows service. Use `.\deposit.bat stop` for a graceful stop.

For a Windows service, run `.\deposit.bat install-service` from an elevated shell. The launcher uses a dedicated Neo4j service identity by default. Before exposing the service to remote clients, configure a private Bolt interface and certificate-validating TLS. Use `.\deposit.bat remove-service` to stop and unregister it; the command does not delete the database or Deposit files. Do not use the local-console and service modes against one Neo4j installation at the same time.

## Run from this checkout

For local development on Windows x64 with PowerShell 5.1, run from the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Run_Server.ps1
```

The first run downloads checksum-pinned tools, builds the plugin and prompts for the Neo4j administrator password. The runtime and data are stored under the sibling `test_harness\runs\local-server` directory. This is a local console launcher; it does not register a service. Later runs reuse the installation and do not rebuild or upgrade it.

```powershell
# Prepare without starting; the port only applies to first setup.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Run_Server.ps1 -SetupOnly -Port 7687

# Inspect or stop the local server.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Run_Server.ps1 -Action Status
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Run_Server.ps1 -Action Stop
```

## Configuration

Neo4j settings belong in `conf/neo4j.conf`. Deposit settings belong in `conf/deposit.properties`.

| Setting | Purpose |
| --- | --- |
| `resourceRoot` | Writable Deposit resource container. The server creates staging, OCFL and work directories below it. The private operational database is `operational/operational.sqlite`, in an owner-only directory. |
| `exportRoot` | Separate writable directory for temporary export files. |
| `secretRoot` | Protected directory for persistent cursor and shutdown-control keys. |
| `download.listenAddress`, `download.port` | Address and port for authenticated resource downloads. The local launcher binds to loopback and allocates a dedicated port. |
| `download.publicBaseUrl` | Stable base URL embedded in exported resource links. Local use should use the loopback URL. Remote access requires HTTPS. |
| `download.tlsKeyStore`, `download.tlsKeyStorePasswordFile` | Optional direct TLS settings for the download listener. Store the password file so only the service identity can read it. |

The local profile binds Bolt to loopback, enables Neo4j authentication, selects Cypher 25, and disables the Neo4j HTTP and HTTPS connectors. It does not provide a query page at `http://localhost:7474`.

For remote access, configure a private Bolt interface and certificate-validating Bolt TLS, restrict inbound traffic to intended clients, and use `neo4j+s://` or `bolt+s://`. Configure the download listener with HTTPS directly or behind a trusted TLS reverse proxy. Do not expose plain HTTP credentials or trust-all Bolt clients. Loopback addresses work only on the server machine.

## Startup and backup notes

Use `status`, `diagnose` and the server logs to inspect operation. `diagnose` includes local paths. Review it before sharing.

Stop Deposit gracefully before taking a filesystem backup. A clean stop drains transactions and maintenance, then checkpoints and closes SQLite. Back up the complete Neo4j store and transaction logs, the entire resource root (including the operational directory, OCFL, staging and storage-admission state), configuration and required secrets as one set. Preserve the service account's ownership and private permissions when copying the files.

Restore the matching set while the server is stopped, using the original Neo4j store files so the persistent store identity is preserved. Set `operationRestoreTime` in `conf/deposit.properties` to the trusted UTC time of the restore before first startup. This advances the operation admission fence so pre-restore operation IDs cannot be replayed. Deposit rejects an incompatible or missing operational database and one bound to a different Neo4j store. Initialize a fresh installation for this alpha format.

The Neo4j store retains scientific history. `DepositSnapshot` stores journal touches with the corresponding entity images or as a touch-only record. A `RevisionDependency` belongs to a `Revision` through `HAS_DEPENDENCY` and stores its `id`, `namespaceId`, `revisionId`, `historyEpochId` and typed historical `entityKey`.
