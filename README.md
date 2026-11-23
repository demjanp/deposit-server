# Deposit Server

Deposit Server is the Neo4j plugin implementation for Deposit 2.0. The public procedure contract for this build is maintained in [runtime/public-api.json](runtime/public-api.json).

## Current state

The alpha uses single-attempt operations. A client registers an operation, executes it once, and can inspect its outcome or request cancellation independently. A cancellation request can race with commit. Check the original operation before starting again after an interrupted call. Server startup reconciles committed storage and cleans up abandoned staging.

The alpha removes prepared-action replay, clone-only staging and resumable uploads. Use the canonical Exchange procedures to stage or export a package, analyze it, and apply it explicitly or clone it atomically. Result readers use short-lived Server leases. Install into a fresh Neo4j database and resource roots for this pre-release format.

The resource and Exchange upload procedures accept contiguous bounded chunks without a checksum argument. Resource publication checks the full declared size and SHA-256. Exchange intake validates complete package and member integrity.

## Project documentation

- [Deposit Server user guide](doc/README.md)
- [Server specification and Windows operations](https://github.com/demjanp/deposit-hub/blob/main/deposit-server.md)
- [Readable procedure API](https://github.com/demjanp/deposit-hub/blob/main/deposit-server-api.md)
- [Windows local launcher](scripts/Run_Server.ps1)


## Windows local installation

The local launcher uses the sibling `..\test_harness` directory for pinned tools, caches, the packaged plugin, and the installation's database, resource roots and logs. It verifies downloaded runtime artifacts and prompts for the Neo4j administrator password at first setup.

### Requirements

- Windows x64 with Windows PowerShell 5.1, .NET Framework and an NTFS volume.
- Access to the pinned artifact sources and Maven repositories when required files are absent from the cache.
- An available loopback Bolt port. The first setup defaults to 7687.

### Setup and run

From the repository root:

```powershell
.\scripts\Run_Server.ps1 -SetupOnly
```

Start the configured installation, inspect its status, or stop it gracefully with:

```powershell
.\scripts\Run_Server.ps1
.\scripts\Run_Server.ps1 -Action Status
.\scripts\Run_Server.ps1 -Action Stop
```

Subsequent launches use the configured plugin and data under `test_harness/runs/local-server`. Preserve an existing installation and its paired resource roots before provisioning a fresh one.

The packaged launcher, public procedure contract and distribution configuration
are under `runtime/`. The Server version comes from its own Git history and
appears in the plugin artifact and runtime discovery.
