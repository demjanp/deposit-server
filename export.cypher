CREATE (n:Namespace {depositVersion: "unknown-version", name: "TestNamespace", description: "", id: "2e851a13-685c-4be8-9e96-b30db0336ce2", versioningType: ""});
CREATE (n:Archive );
CREATE (n:Object {position: [], id: "f407257e-b393-4b02-a687-9e6145259fba"});
CREATE (n:Class {name: "C1", comment: "", position: [], id: "cb93a005-069c-4f2d-a0dc-7c83c2d6e876", order: 1});
CREATE (n:Descriptor {name: "D1", comment: "", id: "07f9c5a3-3fa9-48df-8083-62b8eb849b86", type: "String", order: -1});
CREATE (n:Descriptor {name: "D1", id: "92b8a793-02c6-4681-982f-c4f2d6a9ff7b", value: "val1"});
CREATE (n:Object {position: [], id: "4e963262-44f4-42a2-8815-8bfc09ed73ba"});
CREATE (n:Class {name: "C2", comment: "", position: [], id: "0201811d-db47-455f-a1d4-f1b4f6cca7dc", order: 2});
CREATE (n:Descriptor {name: "D2", comment: "", id: "281326c3-5241-4bf7-b58e-9e643073db0a", type: "String", order: -1});
CREATE (n:Descriptor {name: "D2", id: "12dadbf9-b734-4837-98b9-d7d4c880c3ac", value: "A"});
CREATE (n:Object {position: [], id: "6cd3cca9-b817-4cc1-a5e0-ed088fbd9734"});
CREATE (n:Descriptor {name: "D1", id: "6ecdca55-6736-44ed-9b2a-b42837815e9d", value: "val2"});
CREATE (n:Object {position: [], id: "0eeae06d-1a7d-4472-9d5f-82733fe41e32"});
CREATE (n:Descriptor {name: "D2", id: "d7897a85-650d-49fc-b563-54e6c64fa394", value: "B"});
CREATE (n:Object {position: [], id: "edb35496-1e1c-4cb1-92cc-52b5fb0d956a"});
CREATE (n:Descriptor {name: "D1", id: "8d003c7a-9f02-4ed8-a4b7-b6b369cf6448", value: "val3"});
CREATE (n:Object {position: [], id: "fcf179c2-1a9d-4be2-9e77-cb42ce15150d"});
CREATE (n:Descriptor {name: "D2", id: "cec3cdfd-bb6b-435c-98ee-cd2aacddf251", value: "C"});
CREATE (n:Object {position: [], id: "be1305fb-f83a-4c99-aa32-57d9b22908ed"});
CREATE (n:Descriptor {name: "D1", id: "e22a65e0-11ce-4e39-85c3-638716431ffb", value: "val4"});
CREATE (n:Object {position: [], id: "19951087-39b7-4d13-a333-ce8165d2faae"});
CREATE (n:Descriptor {name: "D2", id: "ee9965b1-c067-4cae-a40e-a41336feec78", value: "D"});
CREATE (n:Object {position: [], id: "d6a5ddbd-138c-498e-9d43-e57ac9883282"});
CREATE (n:Descriptor {name: "D1", id: "bcf4b73b-eea7-4c77-912a-73c0eb15e952", value: "val5"});
CREATE (n:Object {position: [], id: "00825309-588c-461e-9488-02ebf29a3943"});
CREATE (n:Descriptor {name: "D2", id: "cfba190d-405c-4b5e-b1f4-f93f70ae15f2", value: "E"});
CREATE (n:Object {position: [], id: "4ecd6796-e992-47b5-8d83-63742c63e1f3"});
CREATE (n:Descriptor {name: "D1", id: "6ec7cfdf-97d0-4973-bb9e-4c094cec8d4e", value: "val6"});
CREATE (n:Object {position: [], id: "06d6528e-fe5b-41ea-9fd4-d6cae96ad782"});
CREATE (n:Descriptor {name: "D2", id: "84ee4b26-ea4f-4562-8c8a-5a2a856215f2", value: "F"});
CREATE (n:Object {position: [], id: "d17a8b37-0b66-4a9d-8b26-a96f46bd3334"});
CREATE (n:Descriptor {name: "D1", id: "fcd4ff4f-adaf-4f0a-a159-24159e8fea71", value: "val7"});
CREATE (n:Object {position: [], id: "6754ad06-5cda-438a-ac7a-9291830af850"});
CREATE (n:Descriptor {name: "D2", id: "5ade6238-87cd-4293-b8a6-85e2efa89548", value: "G"});
CREATE (n:Object {position: [], id: "ab0e11bb-79fd-49c2-a08e-b797a39fd270"});
CREATE (n:Descriptor {name: "D1", id: "6d913f8f-e1a9-435a-ac80-8139b03efec2", value: "val8"});
CREATE (n:Object {position: [], id: "ec350cc2-cdeb-49be-806a-61b81a888b20"});
CREATE (n:Descriptor {name: "D2", id: "17b69acd-5742-4348-8902-5fd2a1f7dfdf", value: "H"});
MATCH (a), (b) WHERE a.id = "2e851a13-685c-4be8-9e96-b30db0336ce2" AND b.id = "f407257e-b393-4b02-a687-9e6145259fba"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "2e851a13-685c-4be8-9e96-b30db0336ce2" AND b.id = "cb93a005-069c-4f2d-a0dc-7c83c2d6e876"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "f407257e-b393-4b02-a687-9e6145259fba" AND b.id = "cb93a005-069c-4f2d-a0dc-7c83c2d6e876"
CREATE (a)-[:MEMBER_OF {id: "acda75f9-d6be-4458-8a71-90cca18df2bd"}]->(b);
MATCH (a), (b) WHERE a.id = "cb93a005-069c-4f2d-a0dc-7c83c2d6e876" AND b.id = "07f9c5a3-3fa9-48df-8083-62b8eb849b86"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "f407257e-b393-4b02-a687-9e6145259fba" AND b.id = "92b8a793-02c6-4681-982f-c4f2d6a9ff7b"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "2e851a13-685c-4be8-9e96-b30db0336ce2" AND b.id = "4e963262-44f4-42a2-8815-8bfc09ed73ba"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "2e851a13-685c-4be8-9e96-b30db0336ce2" AND b.id = "0201811d-db47-455f-a1d4-f1b4f6cca7dc"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "4e963262-44f4-42a2-8815-8bfc09ed73ba" AND b.id = "0201811d-db47-455f-a1d4-f1b4f6cca7dc"
CREATE (a)-[:MEMBER_OF {id: "67181d50-5cc5-4186-bbdb-c1f021f6b9c4"}]->(b);
MATCH (a), (b) WHERE a.id = "0201811d-db47-455f-a1d4-f1b4f6cca7dc" AND b.id = "281326c3-5241-4bf7-b58e-9e643073db0a"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "4e963262-44f4-42a2-8815-8bfc09ed73ba" AND b.id = "12dadbf9-b734-4837-98b9-d7d4c880c3ac"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "2e851a13-685c-4be8-9e96-b30db0336ce2" AND b.id = "6cd3cca9-b817-4cc1-a5e0-ed088fbd9734"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "6cd3cca9-b817-4cc1-a5e0-ed088fbd9734" AND b.id = "cb93a005-069c-4f2d-a0dc-7c83c2d6e876"
CREATE (a)-[:MEMBER_OF {id: "0144f6f2-0343-483a-885f-df458e51fe05"}]->(b);
MATCH (a), (b) WHERE a.id = "6cd3cca9-b817-4cc1-a5e0-ed088fbd9734" AND b.id = "6ecdca55-6736-44ed-9b2a-b42837815e9d"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "2e851a13-685c-4be8-9e96-b30db0336ce2" AND b.id = "0eeae06d-1a7d-4472-9d5f-82733fe41e32"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "0eeae06d-1a7d-4472-9d5f-82733fe41e32" AND b.id = "0201811d-db47-455f-a1d4-f1b4f6cca7dc"
CREATE (a)-[:MEMBER_OF {id: "6a41ed05-7e0b-456d-a670-43e011d22257"}]->(b);
MATCH (a), (b) WHERE a.id = "0eeae06d-1a7d-4472-9d5f-82733fe41e32" AND b.id = "d7897a85-650d-49fc-b563-54e6c64fa394"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "2e851a13-685c-4be8-9e96-b30db0336ce2" AND b.id = "edb35496-1e1c-4cb1-92cc-52b5fb0d956a"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "edb35496-1e1c-4cb1-92cc-52b5fb0d956a" AND b.id = "cb93a005-069c-4f2d-a0dc-7c83c2d6e876"
CREATE (a)-[:MEMBER_OF {id: "06929168-d4c6-4fd4-9787-23adf508ab0e"}]->(b);
MATCH (a), (b) WHERE a.id = "edb35496-1e1c-4cb1-92cc-52b5fb0d956a" AND b.id = "8d003c7a-9f02-4ed8-a4b7-b6b369cf6448"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "2e851a13-685c-4be8-9e96-b30db0336ce2" AND b.id = "fcf179c2-1a9d-4be2-9e77-cb42ce15150d"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "fcf179c2-1a9d-4be2-9e77-cb42ce15150d" AND b.id = "0201811d-db47-455f-a1d4-f1b4f6cca7dc"
CREATE (a)-[:MEMBER_OF {id: "ee3c3a21-40c0-4445-8c8a-503ef84a2e52"}]->(b);
MATCH (a), (b) WHERE a.id = "fcf179c2-1a9d-4be2-9e77-cb42ce15150d" AND b.id = "cec3cdfd-bb6b-435c-98ee-cd2aacddf251"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "2e851a13-685c-4be8-9e96-b30db0336ce2" AND b.id = "be1305fb-f83a-4c99-aa32-57d9b22908ed"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "be1305fb-f83a-4c99-aa32-57d9b22908ed" AND b.id = "cb93a005-069c-4f2d-a0dc-7c83c2d6e876"
CREATE (a)-[:MEMBER_OF {id: "417e1d75-6ffc-4f39-86ac-b2aba126874e"}]->(b);
MATCH (a), (b) WHERE a.id = "be1305fb-f83a-4c99-aa32-57d9b22908ed" AND b.id = "e22a65e0-11ce-4e39-85c3-638716431ffb"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "2e851a13-685c-4be8-9e96-b30db0336ce2" AND b.id = "19951087-39b7-4d13-a333-ce8165d2faae"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "19951087-39b7-4d13-a333-ce8165d2faae" AND b.id = "0201811d-db47-455f-a1d4-f1b4f6cca7dc"
CREATE (a)-[:MEMBER_OF {id: "bcbdc593-7c27-4918-873b-c1de2d79cfc6"}]->(b);
MATCH (a), (b) WHERE a.id = "19951087-39b7-4d13-a333-ce8165d2faae" AND b.id = "ee9965b1-c067-4cae-a40e-a41336feec78"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "2e851a13-685c-4be8-9e96-b30db0336ce2" AND b.id = "d6a5ddbd-138c-498e-9d43-e57ac9883282"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "d6a5ddbd-138c-498e-9d43-e57ac9883282" AND b.id = "cb93a005-069c-4f2d-a0dc-7c83c2d6e876"
CREATE (a)-[:MEMBER_OF {id: "347c080e-4c98-4e80-a7f7-6e9bec83f6d5"}]->(b);
MATCH (a), (b) WHERE a.id = "d6a5ddbd-138c-498e-9d43-e57ac9883282" AND b.id = "bcf4b73b-eea7-4c77-912a-73c0eb15e952"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "2e851a13-685c-4be8-9e96-b30db0336ce2" AND b.id = "00825309-588c-461e-9488-02ebf29a3943"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "00825309-588c-461e-9488-02ebf29a3943" AND b.id = "0201811d-db47-455f-a1d4-f1b4f6cca7dc"
CREATE (a)-[:MEMBER_OF {id: "309741ca-38df-409a-8192-bae2e8a7ae18"}]->(b);
MATCH (a), (b) WHERE a.id = "00825309-588c-461e-9488-02ebf29a3943" AND b.id = "cfba190d-405c-4b5e-b1f4-f93f70ae15f2"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "2e851a13-685c-4be8-9e96-b30db0336ce2" AND b.id = "4ecd6796-e992-47b5-8d83-63742c63e1f3"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "4ecd6796-e992-47b5-8d83-63742c63e1f3" AND b.id = "cb93a005-069c-4f2d-a0dc-7c83c2d6e876"
CREATE (a)-[:MEMBER_OF {id: "0ca6b822-a360-4fb4-b8f0-52170f1b6f0e"}]->(b);
MATCH (a), (b) WHERE a.id = "4ecd6796-e992-47b5-8d83-63742c63e1f3" AND b.id = "6ec7cfdf-97d0-4973-bb9e-4c094cec8d4e"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "2e851a13-685c-4be8-9e96-b30db0336ce2" AND b.id = "06d6528e-fe5b-41ea-9fd4-d6cae96ad782"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "06d6528e-fe5b-41ea-9fd4-d6cae96ad782" AND b.id = "0201811d-db47-455f-a1d4-f1b4f6cca7dc"
CREATE (a)-[:MEMBER_OF {id: "47ad9bc8-1a83-4513-a8dc-62340218e5ba"}]->(b);
MATCH (a), (b) WHERE a.id = "06d6528e-fe5b-41ea-9fd4-d6cae96ad782" AND b.id = "84ee4b26-ea4f-4562-8c8a-5a2a856215f2"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "2e851a13-685c-4be8-9e96-b30db0336ce2" AND b.id = "d17a8b37-0b66-4a9d-8b26-a96f46bd3334"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "d17a8b37-0b66-4a9d-8b26-a96f46bd3334" AND b.id = "cb93a005-069c-4f2d-a0dc-7c83c2d6e876"
CREATE (a)-[:MEMBER_OF {id: "5d550f6d-cbf0-46a3-8915-a8b1620e27f6"}]->(b);
MATCH (a), (b) WHERE a.id = "d17a8b37-0b66-4a9d-8b26-a96f46bd3334" AND b.id = "fcd4ff4f-adaf-4f0a-a159-24159e8fea71"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "2e851a13-685c-4be8-9e96-b30db0336ce2" AND b.id = "6754ad06-5cda-438a-ac7a-9291830af850"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "6754ad06-5cda-438a-ac7a-9291830af850" AND b.id = "0201811d-db47-455f-a1d4-f1b4f6cca7dc"
CREATE (a)-[:MEMBER_OF {id: "a51e7908-328d-46d7-92b2-966c580443ec"}]->(b);
MATCH (a), (b) WHERE a.id = "6754ad06-5cda-438a-ac7a-9291830af850" AND b.id = "5ade6238-87cd-4293-b8a6-85e2efa89548"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "2e851a13-685c-4be8-9e96-b30db0336ce2" AND b.id = "ab0e11bb-79fd-49c2-a08e-b797a39fd270"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "ab0e11bb-79fd-49c2-a08e-b797a39fd270" AND b.id = "cb93a005-069c-4f2d-a0dc-7c83c2d6e876"
CREATE (a)-[:MEMBER_OF {id: "07a02695-6aff-4a81-ab99-d866f10c4dfd"}]->(b);
MATCH (a), (b) WHERE a.id = "ab0e11bb-79fd-49c2-a08e-b797a39fd270" AND b.id = "6d913f8f-e1a9-435a-ac80-8139b03efec2"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "2e851a13-685c-4be8-9e96-b30db0336ce2" AND b.id = "ec350cc2-cdeb-49be-806a-61b81a888b20"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "ec350cc2-cdeb-49be-806a-61b81a888b20" AND b.id = "0201811d-db47-455f-a1d4-f1b4f6cca7dc"
CREATE (a)-[:MEMBER_OF {id: "36f95fc3-d52c-4fa7-b279-ffa0345e1e0c"}]->(b);
MATCH (a), (b) WHERE a.id = "ec350cc2-cdeb-49be-806a-61b81a888b20" AND b.id = "17b69acd-5742-4348-8902-5fd2a1f7dfdf"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "f407257e-b393-4b02-a687-9e6145259fba" AND b.id = "edb35496-1e1c-4cb1-92cc-52b5fb0d956a"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "relatesTo", id: "d0ef7f9c-d7c5-44ba-9bb6-b9015c6d4def"}]->(b);
MATCH (a), (b) WHERE a.id = "cb93a005-069c-4f2d-a0dc-7c83c2d6e876" AND b.id = "0201811d-db47-455f-a1d4-f1b4f6cca7dc"
CREATE (a)-[:RELATED_TO {comment: "", label: "relatesTo", id: "a41c4be6-4a85-4f37-9ca0-2b89b98c3c02"}]->(b);
MATCH (a), (b) WHERE a.id = "edb35496-1e1c-4cb1-92cc-52b5fb0d956a" AND b.id = "4e963262-44f4-42a2-8815-8bfc09ed73ba"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "relatesTo", id: "55c104e8-ad59-42b9-b354-bca7523396cd"}]->(b);
MATCH (a), (b) WHERE a.id = "6cd3cca9-b817-4cc1-a5e0-ed088fbd9734" AND b.id = "fcf179c2-1a9d-4be2-9e77-cb42ce15150d"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "relatesTo", id: "35a4f3f6-6c78-4fad-8bf6-93fd52d86a90"}]->(b);
MATCH (a), (b) WHERE a.id = "0201811d-db47-455f-a1d4-f1b4f6cca7dc" AND b.id = "cb93a005-069c-4f2d-a0dc-7c83c2d6e876"
CREATE (a)-[:RELATED_TO {comment: "", label: "relatesTo", id: "f6a941f2-6165-4f4e-a541-f1dc04bcf923"}]->(b);
MATCH (a), (b) WHERE a.id = "fcf179c2-1a9d-4be2-9e77-cb42ce15150d" AND b.id = "be1305fb-f83a-4c99-aa32-57d9b22908ed"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "relatesTo", id: "be416858-0373-48da-ad14-c25a404f4c92"}]->(b);
MATCH (a), (b) WHERE a.id = "be1305fb-f83a-4c99-aa32-57d9b22908ed" AND b.id = "19951087-39b7-4d13-a333-ce8165d2faae"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "relatesTo", id: "119a0d50-0764-43d7-8777-a3f06bd5cae6"}]->(b);
MATCH (a), (b) WHERE a.id = "d6a5ddbd-138c-498e-9d43-e57ac9883282" AND b.id = "00825309-588c-461e-9488-02ebf29a3943"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "relatesTo", id: "5ba94354-a088-49cc-9572-78e05eed7b0b"}]->(b);
MATCH (a), (b) WHERE a.id = "d6a5ddbd-138c-498e-9d43-e57ac9883282" AND b.id = "4ecd6796-e992-47b5-8d83-63742c63e1f3"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "relatesTo", id: "fe7271a7-837c-4d39-bb84-6d482dfa51bc"}]->(b);
MATCH (a), (b) WHERE a.id = "4ecd6796-e992-47b5-8d83-63742c63e1f3" AND b.id = "d17a8b37-0b66-4a9d-8b26-a96f46bd3334"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "relatesTo", id: "31771d99-36ba-4fc1-a3b3-d13689052fe5"}]->(b);
MATCH (a), (b) WHERE a.id = "d17a8b37-0b66-4a9d-8b26-a96f46bd3334" AND b.id = "ab0e11bb-79fd-49c2-a08e-b797a39fd270"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "relatesTo", id: "ae0cfe6e-4f23-4a81-aee1-cf4287defee3"}]->(b);
MATCH (a), (b) WHERE a.id = "ab0e11bb-79fd-49c2-a08e-b797a39fd270" AND b.id = "ec350cc2-cdeb-49be-806a-61b81a888b20"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "relatesTo", id: "171e2b25-f60f-4947-9483-56344c4abfff"}]->(b);
