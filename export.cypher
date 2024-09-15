CREATE (n:Namespace {depositVersion: "2.0.0-dev.49-dirty", name: "TestNamespace", description: "", id: "28491f19-98a0-416e-82a0-cba086a2d044", versioningType: ""});
CREATE (n:Archive );
CREATE (n:Object {position: [], id: "c36de9d3-c8dd-4589-8250-c3ea5ca8d519"});
CREATE (n:Class {name: "C2", comment: "", position: [], id: "257cc730-aa87-4d1a-b0d3-7c29b47db7d2", order: 1});
CREATE (n:Descriptor {name: "D1", comment: "", id: "fab5d476-64f2-49c0-aae3-4edcfc2998d2", type: "String", order: -1});
CREATE (n:Descriptor {name: "D1", id: "e9234e2f-de53-4c56-bbfa-e514066d2b0b", value: "value1"});
CREATE (n:Object {position: [], id: "71a95124-b963-4321-849d-5955673bffaf"});
CREATE (n:Class {name: "C1", comment: "", position: [], id: "87aa5d22-27d6-4002-86e0-7aa350e3b6d5", order: 2});
CREATE (n:Object {position: [], id: "5057c04a-3980-4ce9-910a-2bd989e5adb5"});
CREATE (n:Descriptor {name: "D1", id: "5b5b2d0c-4ab2-4c7c-b69b-587df4fc5d1a", value: "value2"});
CREATE (n:Object {position: [], id: "d941ec2d-0c72-4761-93f6-6391905387a7"});
CREATE (n:Object {position: [], id: "8b3119cf-0529-42fb-b6f4-760696323352"});
CREATE (n:Descriptor {name: "D1", id: "1f92ea2a-804d-41ef-97a8-bc061ca3c0fd", value: "value3"});
CREATE (n:Object {position: [], id: "e447d28a-87e0-465e-8f62-e8f8a172f845"});
CREATE (n:Object {position: [], id: "713deae6-d469-4273-ae3e-242f57c486a0"});
CREATE (n:Descriptor {name: "D1", id: "68037715-5be7-400f-9318-831d60aa39e8", value: "value3"});
CREATE (n:Object {position: [], id: "4e0d02c5-df45-48c4-9c28-2a2f8319b447"});
MATCH (a), (b) WHERE a.id = "28491f19-98a0-416e-82a0-cba086a2d044" AND b.id = "c36de9d3-c8dd-4589-8250-c3ea5ca8d519"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "28491f19-98a0-416e-82a0-cba086a2d044" AND b.id = "257cc730-aa87-4d1a-b0d3-7c29b47db7d2"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "c36de9d3-c8dd-4589-8250-c3ea5ca8d519" AND b.id = "257cc730-aa87-4d1a-b0d3-7c29b47db7d2"
CREATE (a)-[:MEMBER_OF {id: "21986f9d-834a-4f2b-9711-898306456422"}]->(b);
MATCH (a), (b) WHERE a.id = "257cc730-aa87-4d1a-b0d3-7c29b47db7d2" AND b.id = "fab5d476-64f2-49c0-aae3-4edcfc2998d2"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "c36de9d3-c8dd-4589-8250-c3ea5ca8d519" AND b.id = "e9234e2f-de53-4c56-bbfa-e514066d2b0b"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "28491f19-98a0-416e-82a0-cba086a2d044" AND b.id = "71a95124-b963-4321-849d-5955673bffaf"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "28491f19-98a0-416e-82a0-cba086a2d044" AND b.id = "87aa5d22-27d6-4002-86e0-7aa350e3b6d5"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "71a95124-b963-4321-849d-5955673bffaf" AND b.id = "87aa5d22-27d6-4002-86e0-7aa350e3b6d5"
CREATE (a)-[:MEMBER_OF {id: "d31a1940-3cde-487d-a884-470a061e47be"}]->(b);
MATCH (a), (b) WHERE a.id = "28491f19-98a0-416e-82a0-cba086a2d044" AND b.id = "5057c04a-3980-4ce9-910a-2bd989e5adb5"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "5057c04a-3980-4ce9-910a-2bd989e5adb5" AND b.id = "257cc730-aa87-4d1a-b0d3-7c29b47db7d2"
CREATE (a)-[:MEMBER_OF {id: "fe7743af-5594-402f-bc06-d2105290bf70"}]->(b);
MATCH (a), (b) WHERE a.id = "5057c04a-3980-4ce9-910a-2bd989e5adb5" AND b.id = "5b5b2d0c-4ab2-4c7c-b69b-587df4fc5d1a"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "28491f19-98a0-416e-82a0-cba086a2d044" AND b.id = "d941ec2d-0c72-4761-93f6-6391905387a7"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "d941ec2d-0c72-4761-93f6-6391905387a7" AND b.id = "87aa5d22-27d6-4002-86e0-7aa350e3b6d5"
CREATE (a)-[:MEMBER_OF {id: "a1027015-ed2e-41e3-b059-a8b4100f9a71"}]->(b);
MATCH (a), (b) WHERE a.id = "28491f19-98a0-416e-82a0-cba086a2d044" AND b.id = "8b3119cf-0529-42fb-b6f4-760696323352"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "8b3119cf-0529-42fb-b6f4-760696323352" AND b.id = "257cc730-aa87-4d1a-b0d3-7c29b47db7d2"
CREATE (a)-[:MEMBER_OF {id: "6dc06c66-e322-426a-b7c9-81736c474d99"}]->(b);
MATCH (a), (b) WHERE a.id = "8b3119cf-0529-42fb-b6f4-760696323352" AND b.id = "1f92ea2a-804d-41ef-97a8-bc061ca3c0fd"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "28491f19-98a0-416e-82a0-cba086a2d044" AND b.id = "e447d28a-87e0-465e-8f62-e8f8a172f845"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "e447d28a-87e0-465e-8f62-e8f8a172f845" AND b.id = "87aa5d22-27d6-4002-86e0-7aa350e3b6d5"
CREATE (a)-[:MEMBER_OF {id: "d99625cc-be2c-4a23-890e-9e4bfc12ca79"}]->(b);
MATCH (a), (b) WHERE a.id = "28491f19-98a0-416e-82a0-cba086a2d044" AND b.id = "713deae6-d469-4273-ae3e-242f57c486a0"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "713deae6-d469-4273-ae3e-242f57c486a0" AND b.id = "257cc730-aa87-4d1a-b0d3-7c29b47db7d2"
CREATE (a)-[:MEMBER_OF {id: "11daf5f0-9316-436e-b27a-d3bd17db8dfa"}]->(b);
MATCH (a), (b) WHERE a.id = "713deae6-d469-4273-ae3e-242f57c486a0" AND b.id = "68037715-5be7-400f-9318-831d60aa39e8"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "28491f19-98a0-416e-82a0-cba086a2d044" AND b.id = "4e0d02c5-df45-48c4-9c28-2a2f8319b447"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "4e0d02c5-df45-48c4-9c28-2a2f8319b447" AND b.id = "87aa5d22-27d6-4002-86e0-7aa350e3b6d5"
CREATE (a)-[:MEMBER_OF {id: "3c0bd5a7-53bf-4082-8097-9dfe85b51f7d"}]->(b);
MATCH (a), (b) WHERE a.id = "87aa5d22-27d6-4002-86e0-7aa350e3b6d5" AND b.id = "257cc730-aa87-4d1a-b0d3-7c29b47db7d2"
CREATE (a)-[:RELATED_TO {comment: "", label: "contains", id: "ce52f26a-10bb-40ea-a611-939653a2d3a3"}]->(b);
MATCH (a), (b) WHERE a.id = "71a95124-b963-4321-849d-5955673bffaf" AND b.id = "c36de9d3-c8dd-4589-8250-c3ea5ca8d519"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "c6e15969-d1e1-4bc7-9145-7e54b79577eb"}]->(b);
MATCH (a), (b) WHERE a.id = "71a95124-b963-4321-849d-5955673bffaf" AND b.id = "5057c04a-3980-4ce9-910a-2bd989e5adb5"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "8612217d-a6e0-4060-9cf7-abed7b96a19f"}]->(b);
MATCH (a), (b) WHERE a.id = "71a95124-b963-4321-849d-5955673bffaf" AND b.id = "8b3119cf-0529-42fb-b6f4-760696323352"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "23e0d220-0168-4584-8a73-a9e1cbb0449f"}]->(b);
MATCH (a), (b) WHERE a.id = "71a95124-b963-4321-849d-5955673bffaf" AND b.id = "713deae6-d469-4273-ae3e-242f57c486a0"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "f16bde18-6e32-4221-820d-72c8dcbeba4b"}]->(b);
MATCH (a), (b) WHERE a.id = "d941ec2d-0c72-4761-93f6-6391905387a7" AND b.id = "5057c04a-3980-4ce9-910a-2bd989e5adb5"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "b709deff-633a-4dac-b9c7-ee03527a369c"}]->(b);
