# Installation and configuration

## Install the Windows x64 release

Deposit Server 2.0.1 uses Neo4j Community 2026.05.0, Temurin JDK 21.0.12.1+1, and Cypher 25. The JDK and Neo4j are separate downloads.

1. Download `deposit-server-2.0.1.zip` from [GitHub Releases](https://github.com/demjanp/deposit-server/releases).
2. Download [Temurin JDK 21.0.12.1+1 for Windows x64](https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip) and [Neo4j Community 2026.05.0 for Windows](https://dist.neo4j.org/neo4j-community-2026.05.0-windows.zip).
3. Extract all three archives to separate directories. Use a fresh Neo4j installation. Open Command Prompt in the extracted Deposit directory containing `deposit.bat`.
4. Set the initial Neo4j password and start Deposit. Replace the quoted paths with your Neo4j, Java, resource storage, and temporary export directories.

   ```bat
   deposit.bat init-password "C:\Neo4j" "C:\Java\jdk-21"
   deposit.bat setup "C:\Neo4j" "C:\Java\jdk-21" "C:\Deposit\resources" "C:\Deposit\exports" 7687
   deposit.bat start
   deposit.bat status
   ```

   The password initializer prompts interactively. Use at least eight characters without whitespace, quotes, `#`, or backslash. The final setup argument is the local Bolt port and defaults to `7687`.
5. Follow the [quickstart](quickstart.md) to open Cypher Shell, create a Deposit administrator, and load the sample data. Use `deposit.bat stop` for a graceful stop.

Setup copies the plugins, selects Cypher 25, and configures Bolt and the Deposit download listener on loopback. Neo4j HTTP and HTTPS are disabled. The launcher records the installation paths and mode in `deposit.install.json` in the extracted Deposit directory.

## Windows service

Stop the console instance with `deposit.bat stop` first. Open an elevated Command Prompt in the Deposit directory:

```bat
deposit.bat install-service
deposit.bat start
deposit.bat status
```

The launcher uses a dedicated virtual service account by default. Use `deposit.bat stop` to stop the service gracefully and `deposit.bat remove-service` to unregister it while retaining the data. For remote clients, configure a private Bolt interface and certificate-validating TLS before making the service reachable.

## Run from a Git checkout

On Windows x64, open Command Prompt at the root of a full Git checkout:

```bat
scripts\Run_Server.bat
scripts\Run_Shell.bat
```

The first server run downloads the pinned tools, builds the plugin, and prompts for the initial Neo4j password. Tools and generated files go under the sibling `test_harness` directory. The persistent console installation and data live in `test_harness\runs\local-server`. Later runs reuse that installation. Open the shell after the server starts.

```bat
scripts\Run_Server.bat -SetupOnly -Port 7687
scripts\Run_Server.bat -Action Status
scripts\Run_Server.bat -Action Stop
```

`-Port` applies during first setup. For a custom port, give the shell launcher the matching address, for example `scripts\Run_Shell.bat -Address bolt://127.0.0.1:7688`.

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

Restore the matching set while the server is stopped, using the original Neo4j store files so the persistent store identity is preserved. Set `operationRestoreTime` in `conf/deposit.properties` to the trusted UTC time of the restore before first startup. This advances the operation admission fence so pre-restore operation IDs cannot be replayed. Deposit requires the operational database that belongs to the restored Neo4j store.
