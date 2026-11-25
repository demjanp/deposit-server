# deposit-server

**deposit-server** is the server component of **Deposit**, implemented as a plugin to the Neo4j graph database. It manages scientific records, their relationships, attached files, and revision history for connected applications.

## Features

- **Flexible, validated data model.** *Namespaces* organize separate collections. *Classes* define record types and permitted, typed fields; *Objects* are individual records, with *Descriptors* holding their values. Declared relationships connect records, and the server validates their structure.
- **Queries and reusable patterns.** Deposit Query Language searches, filters, and summarizes records and their relationships. Saved *Patterns* describe structures for matching records and mapping imported data.
- **Import and exchange.** Load and save CSV, Excel (.xlsx), and GeoPackage data, with import mapping and analysis before applying changes. Exchange selected records or whole Namespaces through portable packages, or clone a Namespace.
- **Files and spatial data.** Store file attachments using OCFL (Oxford Common File Layout) and manage spatial geometries as part of the data model.
- **Revision history.** Optional history lets users inspect changes, undo or redo operations, and restore earlier states of a Namespace.
- **Multi-user access.** Multiple local or remote applications can share one server. Deposit accounts have per-Namespace viewer, editor, and owner roles. The server enforces permissions, detects edit conflicts, and tracks operation outcomes.
- **Application API.** Applications use `deposit.*` procedures over Neo4j's Bolt protocol. The [procedure reference](doc/procedures.md) lists the procedures. Runtime discovery reports the version and supported features. A separate HTTP endpoint serves file downloads.

## Platform

The current distribution supports Windows process and service operation. Applications can use the API directly or through the [deposit-python wrapper](https://github.com/demjanp/deposit-python).

## Getting started

To run from this checkout, follow the [checkout installation instructions](doc/installation.md#run-from-a-git-checkout) and the [quickstart](doc/quickstart.md). Tracked Cypher procedures accept calls with just the domain arguments and Deposit token. The server generates the operation ID when you omit it. See [tracked operations](doc/procedures.md#tracked-operations) for explicit registration and outcome lookup.

For a Windows release, download the Deposit Server 2.x.x ZIP and follow the installation guide and quickstart included in the archive. [GitHub Releases](https://github.com/demjanp/deposit-server/releases)

See the [user guide](doc/README.md) for the full documentation and the [release notes](RELEASE_NOTES.md) for Windows distribution details.

## Build the release ZIP

On Windows x64, open Command Prompt at the root of a clean, full Git checkout. Check out the release commit with exactly one stable version tag. Use a working directory outside the checkout.

```bat
scripts\Build_Release.bat -WorkRoot "C:\DepositBuild"
```

The builder fetches the pinned build inputs, compiles the server plugin, and writes `deposit-server-2.x.x.zip` to the working directory using the version from the Git tag. It skips tests and reuses cached downloads on subsequent builds. The output ZIP must not already exist.

## Copyright and license

Copyright © 2026 Peter Demján.

Distributed under the MIT License.
