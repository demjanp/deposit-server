# deposit-server

**deposit-server** is the server component of **Deposit**, implemented as a plugin to the Neo4j graph database. It manages scientific records, their relationships, attached files, and revision history for connected applications.

## Features

- **Flexible, validated data model.** *Namespaces* organize separate collections. *Classes* define record types and permitted, typed fields; *Objects* are individual records, with *Descriptors* holding their values. Declared relationships connect records, and the server validates their structure.
- **Queries and reusable patterns.** Deposit Query Language searches, filters, and summarizes records and their relationships. Saved *Patterns* describe structures for matching records and mapping imported data.
- **Import and exchange.** Load and save CSV, Excel (.xlsx), and GeoPackage data, with import mapping and analysis before applying changes. Exchange selected records or whole Namespaces through portable packages, or clone a Namespace.
- **Files and spatial data.** Store file attachments using OCFL (Oxford Common File Layout) and manage spatial geometries as part of the data model.
- **Revision history.** Optional history lets users inspect changes, undo or redo operations, and restore earlier states of a Namespace.
- **Multi-user access.** Multiple local or remote applications can share one server. Deposit accounts have per-Namespace viewer, editor, and owner roles. The server enforces permissions, detects edit conflicts, and tracks operation outcomes.
- **Application API.** Applications use `deposit.*` procedures over Neo4j's Bolt protocol. The [API manifest](runtime/public-api.json) lists the procedures; runtime discovery reports the version and supported features. A separate authenticated HTTP endpoint serves file downloads only.

## Platform

The current distribution supports Windows process and service operation. Applications can use the API directly or through the [deposit-python wrapper](https://github.com/demjanp/deposit-python).

## Getting started

Start with [Installation](doc/installation.md) and [Quickstart](doc/quickstart.md). For further guidance and reference, see the [User guide](doc/README.md), [Query language](doc/query-language.md), [Procedure reference](doc/procedures.md), and [Technical specification](https://github.com/demjanp/deposit-hub/blob/main/deposit-server.md).

## Copyright and license

Copyright © 2026 Peter Demján.

Distributed under the MIT License.
