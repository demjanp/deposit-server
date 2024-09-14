CREATE (n:Namespace {depositVersion: "2.0.0-dev.48-dirty", name: "TestNamespace", description: "", id: "ae4c4a8f-7d09-43d1-969e-0cc6f0cb822c", versioningType: ""});
CREATE (n:Archive );
CREATE (n:Object {position: [], id: "76698614-9847-4519-81e8-63e856514122"});
CREATE (n:Class {name: "C1", comment: "", position: [], id: "16c3b0c1-861f-4ff9-a4c1-e47f55c662a5", order: 1});
CREATE (n:Descriptor {name: "D1", comment: "", id: "2941a16e-3bb4-4a3e-8c3c-1b05caa8a891", type: "String", order: -1});
CREATE (n:Descriptor {name: "D1", id: "76bdf45a-b76a-47bd-a799-78a14ac8683f", value: "value1"});
CREATE (n:Object {position: [], id: "ede72732-296a-426e-b64b-e10ac6c3926f"});
CREATE (n:Class {name: "C2", comment: "", position: [], id: "2e09b7ae-d100-40df-8bb1-d6a92435a657", order: 2});
CREATE (n:Object {position: [], id: "f787cc1d-3ec9-42cc-bb2c-4227442efaff"});
CREATE (n:Descriptor {name: "D1", id: "83fdd73c-6bea-41c9-a37b-11a7a8f4f1cb", value: "value2"});
CREATE (n:Object {position: [], id: "32f0a3cc-5ace-4a55-af0d-4443c40d2946"});
CREATE (n:Object {position: [], id: "be7c0533-21e0-407e-83a9-8d6726235997"});
CREATE (n:Object {position: [], id: "3e432f40-a030-4a63-8954-1f5e4b8c3ad5"});
CREATE (n:Object {position: [], id: "eb33bf6f-4593-48f1-ae3d-b2e78d58aef0"});
CREATE (n:Object {position: [], id: "8c57b57e-4437-45f0-83c0-7be596a7c15a"});
CREATE (n:Object {position: [], id: "0913c40a-b048-4edb-a819-3adb895eebd8"});
CREATE (n:Object {position: [], id: "d3f5aa7d-6ee8-4165-9784-6eae7c7a2540"});
MATCH (a), (b) WHERE a.id = "ae4c4a8f-7d09-43d1-969e-0cc6f0cb822c" AND b.id = "76698614-9847-4519-81e8-63e856514122"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "ae4c4a8f-7d09-43d1-969e-0cc6f0cb822c" AND b.id = "16c3b0c1-861f-4ff9-a4c1-e47f55c662a5"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "76698614-9847-4519-81e8-63e856514122" AND b.id = "16c3b0c1-861f-4ff9-a4c1-e47f55c662a5"
CREATE (a)-[:MEMBER_OF {id: "58162c07-8896-4af1-b9f7-bb4a03123ab0"}]->(b);
MATCH (a), (b) WHERE a.id = "16c3b0c1-861f-4ff9-a4c1-e47f55c662a5" AND b.id = "2941a16e-3bb4-4a3e-8c3c-1b05caa8a891"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "76698614-9847-4519-81e8-63e856514122" AND b.id = "76bdf45a-b76a-47bd-a799-78a14ac8683f"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "ae4c4a8f-7d09-43d1-969e-0cc6f0cb822c" AND b.id = "ede72732-296a-426e-b64b-e10ac6c3926f"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "ae4c4a8f-7d09-43d1-969e-0cc6f0cb822c" AND b.id = "2e09b7ae-d100-40df-8bb1-d6a92435a657"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "ede72732-296a-426e-b64b-e10ac6c3926f" AND b.id = "2e09b7ae-d100-40df-8bb1-d6a92435a657"
CREATE (a)-[:MEMBER_OF {id: "802aa757-eee2-44f3-b9bf-0583f4e62b28"}]->(b);
MATCH (a), (b) WHERE a.id = "ae4c4a8f-7d09-43d1-969e-0cc6f0cb822c" AND b.id = "f787cc1d-3ec9-42cc-bb2c-4227442efaff"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "f787cc1d-3ec9-42cc-bb2c-4227442efaff" AND b.id = "16c3b0c1-861f-4ff9-a4c1-e47f55c662a5"
CREATE (a)-[:MEMBER_OF {id: "4b1af709-3b82-4bbc-8354-f5fe6d684722"}]->(b);
MATCH (a), (b) WHERE a.id = "f787cc1d-3ec9-42cc-bb2c-4227442efaff" AND b.id = "83fdd73c-6bea-41c9-a37b-11a7a8f4f1cb"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "ae4c4a8f-7d09-43d1-969e-0cc6f0cb822c" AND b.id = "32f0a3cc-5ace-4a55-af0d-4443c40d2946"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "32f0a3cc-5ace-4a55-af0d-4443c40d2946" AND b.id = "2e09b7ae-d100-40df-8bb1-d6a92435a657"
CREATE (a)-[:MEMBER_OF {id: "e067e3f7-b0f8-43ec-a35d-c6113d2a3753"}]->(b);
MATCH (a), (b) WHERE a.id = "ae4c4a8f-7d09-43d1-969e-0cc6f0cb822c" AND b.id = "be7c0533-21e0-407e-83a9-8d6726235997"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "be7c0533-21e0-407e-83a9-8d6726235997" AND b.id = "16c3b0c1-861f-4ff9-a4c1-e47f55c662a5"
CREATE (a)-[:MEMBER_OF {id: "3c07e6d3-a1a2-47f3-b725-9c560e9abc1a"}]->(b);
MATCH (a), (b) WHERE a.id = "ae4c4a8f-7d09-43d1-969e-0cc6f0cb822c" AND b.id = "3e432f40-a030-4a63-8954-1f5e4b8c3ad5"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "3e432f40-a030-4a63-8954-1f5e4b8c3ad5" AND b.id = "2e09b7ae-d100-40df-8bb1-d6a92435a657"
CREATE (a)-[:MEMBER_OF {id: "8f84529a-a40c-4570-8ea8-5c0f21e20f17"}]->(b);
MATCH (a), (b) WHERE a.id = "ae4c4a8f-7d09-43d1-969e-0cc6f0cb822c" AND b.id = "eb33bf6f-4593-48f1-ae3d-b2e78d58aef0"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "eb33bf6f-4593-48f1-ae3d-b2e78d58aef0" AND b.id = "16c3b0c1-861f-4ff9-a4c1-e47f55c662a5"
CREATE (a)-[:MEMBER_OF {id: "acd90822-99c9-4115-aa17-87f01a04243f"}]->(b);
MATCH (a), (b) WHERE a.id = "ae4c4a8f-7d09-43d1-969e-0cc6f0cb822c" AND b.id = "8c57b57e-4437-45f0-83c0-7be596a7c15a"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "8c57b57e-4437-45f0-83c0-7be596a7c15a" AND b.id = "2e09b7ae-d100-40df-8bb1-d6a92435a657"
CREATE (a)-[:MEMBER_OF {id: "bc1103a1-60f7-488b-a0ad-33c8781383d7"}]->(b);
MATCH (a), (b) WHERE a.id = "ae4c4a8f-7d09-43d1-969e-0cc6f0cb822c" AND b.id = "0913c40a-b048-4edb-a819-3adb895eebd8"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "0913c40a-b048-4edb-a819-3adb895eebd8" AND b.id = "16c3b0c1-861f-4ff9-a4c1-e47f55c662a5"
CREATE (a)-[:MEMBER_OF {id: "06c4ce18-a015-4a76-889c-80f8caa1b8d3"}]->(b);
MATCH (a), (b) WHERE a.id = "ae4c4a8f-7d09-43d1-969e-0cc6f0cb822c" AND b.id = "d3f5aa7d-6ee8-4165-9784-6eae7c7a2540"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "d3f5aa7d-6ee8-4165-9784-6eae7c7a2540" AND b.id = "2e09b7ae-d100-40df-8bb1-d6a92435a657"
CREATE (a)-[:MEMBER_OF {id: "60b7fc9e-b662-4edd-b8cb-ee8d7c4ca75c"}]->(b);
MATCH (a), (b) WHERE a.id = "16c3b0c1-861f-4ff9-a4c1-e47f55c662a5" AND b.id = "2e09b7ae-d100-40df-8bb1-d6a92435a657"
CREATE (a)-[:RELATED_TO {comment: "", label: "contains", id: "68558b38-b8e6-4ab2-9b51-94dd9379e75c"}]->(b);
MATCH (a), (b) WHERE a.id = "76698614-9847-4519-81e8-63e856514122" AND b.id = "ede72732-296a-426e-b64b-e10ac6c3926f"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "40ebd166-4659-4c82-bc92-2fd161bd7426"}]->(b);
MATCH (a), (b) WHERE a.id = "76698614-9847-4519-81e8-63e856514122" AND b.id = "32f0a3cc-5ace-4a55-af0d-4443c40d2946"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "a7336141-8344-45b5-9ab6-ab3e3902481b"}]->(b);
MATCH (a), (b) WHERE a.id = "f787cc1d-3ec9-42cc-bb2c-4227442efaff" AND b.id = "3e432f40-a030-4a63-8954-1f5e4b8c3ad5"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "e69e16d2-c604-4992-bfd2-c64b43751ccc"}]->(b);
MATCH (a), (b) WHERE a.id = "f787cc1d-3ec9-42cc-bb2c-4227442efaff" AND b.id = "8c57b57e-4437-45f0-83c0-7be596a7c15a"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "5d1d4f30-27ea-4f24-8528-aa32c7426297"}]->(b);
MATCH (a), (b) WHERE a.id = "f787cc1d-3ec9-42cc-bb2c-4227442efaff" AND b.id = "d3f5aa7d-6ee8-4165-9784-6eae7c7a2540"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "c295d65e-1641-47e0-a9ed-e94b05d12ec2"}]->(b);
