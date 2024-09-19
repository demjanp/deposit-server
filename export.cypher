MATCH (n) WITH n DETACH DELETE n;
CREATE (n:Namespace {depositVersion: "2.0.0-dev.51-dirty", name: "TestNamespace", description: "", id: "219c2bbb-985f-4dfd-9425-a70450f7961c", versioningType: ""});
CREATE (n:Object {position: [], id: "15f481db-cb1e-439e-bb36-624f7b8954e3"});
CREATE (n:Class {name: "Country", comment: "", id: "aaf4a42e-2b85-4b78-b216-18934453a10d", position: [], order: 1});
CREATE (n:Descriptor {name: "Name", comment: "", id: "9286c010-be4c-49d6-a053-3aaefdbcdd39", type: "String", order: -1});
CREATE (n:Descriptor {name: "Name", id: "b211e841-aea8-4338-9b02-982132a74f05", value: "Austria"});
CREATE (n:Object {position: [], id: "7199db51-14a6-4888-9ea8-cd4d6712463a"});
CREATE (n:Class {name: "City", comment: "", position: [], id: "b4375feb-f434-450b-be3e-efe3fb33fed4", order: 2});
CREATE (n:Descriptor {name: "Name", comment: "", id: "36c5585c-b402-4a40-a76a-9663cb669ebe", type: "String", order: -1});
CREATE (n:Descriptor {name: "Name", id: "cb20968c-9034-487b-8777-1b8889ee2beb", value: "Vienna"});
CREATE (n:Object {position: [], id: "18a0b15b-b64f-47c7-b422-5a9c990b4c2a"});
CREATE (n:Class {name: "Company", comment: "", id: "08eb8ea7-fe4d-4d67-8a39-a93c6b1e1005", position: [], order: 3});
CREATE (n:Descriptor {name: "Name", comment: "", id: "0b0e8bc2-fd3a-49d4-b7b1-e5ad6776c813", type: "String", order: -1});
CREATE (n:Descriptor {name: "Name", id: "64008966-9d76-4ce0-9025-54754d000b92", value: "Velocipeds"});
CREATE (n:Object {position: [], id: "075b257d-6ec7-42a7-9dc0-704e0e6d9723"});
CREATE (n:Descriptor {name: "Name", id: "4d2001ca-c007-4fc6-9d27-8d85741020f4", value: "Voleyballs"});
CREATE (n:Object {position: [], id: "a1f4e795-feb6-4157-bb82-05b4bcef3f41"});
CREATE (n:Descriptor {name: "Name", id: "9e2644c6-8bc1-495d-b09f-c80fae087b6d", value: "Gamma"});
CREATE (n:Object {position: [], id: "23183af4-f37e-4d54-a6b9-3d26793b1182"});
CREATE (n:Class {name: "Person", comment: "", id: "1aea3d83-156c-4df9-82b2-d0504312871d", position: [], order: 4});
CREATE (n:Descriptor {name: "Name", comment: "", id: "34561d5f-1d5f-4307-b91b-5d7630b1916a", type: "String", order: -1});
CREATE (n:Descriptor {name: "Name", id: "81f4b822-0ef4-49f7-897d-d5fb9a171b85", value: "Adam"});
CREATE (n:DResource {storedFilename: "Adam-b32e143d-6afd-43c5-823a-f853b6d20313.tmp", imageFormat: "", filename: "Adam.tmp", id: "5a054c3d-31d7-44b9-9c3a-14374c678f10"});
CREATE (n:Descriptor {name: "File", comment: "", id: "931f7c41-ca75-4601-a7ee-bd881d22ed9a", type: "DResource", order: -1});
CREATE (n:Descriptor {name: "File", id: "cd161fd6-0586-4f8e-bf42-117e26727e3e", value: "Adam.tmp"});
CREATE (n:Object {position: [], id: "190673e3-f115-4455-ac30-59ce14b4098c"});
CREATE (n:Descriptor {name: "Name", id: "5d3222a3-d8d6-40c2-ae94-d980e375e711", value: "Alex"});
CREATE (n:DResource {storedFilename: "Alex-9d12679d-5f18-468a-a7f1-889ac771727c.tmp", imageFormat: "", filename: "Alex.tmp", id: "1c880c36-de6f-47e6-a5bf-d73e1e9e8429"});
CREATE (n:Descriptor {name: "File", id: "4e506f0c-c0fe-40e4-89b4-a42d5dbd3b90", value: "Alex.tmp"});
CREATE (n:Object {position: [], id: "9127f425-a6be-471a-b60d-85c041614d33"});
CREATE (n:Descriptor {name: "Name", id: "9481a3ee-ce51-4b06-aa6f-42880b153e12", value: "Linz"});
CREATE (n:Object {position: [], id: "14069664-71bb-4896-9162-b3f50c6c21b9"});
CREATE (n:Descriptor {name: "Name", id: "c8b56754-50ad-4306-a90c-0aa30e809a63", value: "Hugo"});
CREATE (n:DResource {storedFilename: "Hugo-a7025923-b364-4f6e-b8eb-f98e9c65770a.tmp", imageFormat: "", filename: "Hugo.tmp", id: "c4c70068-9bf2-4285-af91-ebc5104389d5"});
CREATE (n:Descriptor {name: "File", id: "bb86d1f0-faa5-4027-a438-a1210a411e3a", value: "Hugo.tmp"});
CREATE (n:Object {position: [], id: "ff34dcff-0db4-461c-b49a-1c2c2f040a86"});
CREATE (n:Descriptor {name: "Name", id: "cea2a3c6-779d-440c-a2aa-4c0a81cc9873", value: "Lisa"});
CREATE (n:DResource {storedFilename: "Lisa-3d4df577-51d9-47ca-9f3e-d632323e4f21.tmp", imageFormat: "", filename: "Lisa.tmp", id: "e51a47da-ca2a-4b15-8865-48c66b99c963"});
CREATE (n:Descriptor {name: "File", id: "63effc4f-7e57-4d85-b30b-5fbd1dd9abea", value: "Lisa.tmp"});
CREATE (n:Object {position: [], id: "9a58f942-77fa-45cb-b64f-8ef122f2b6ae"});
CREATE (n:Descriptor {name: "Name", id: "b36220b3-f9b1-4ca7-8843-81d300760773", value: "Anna"});
CREATE (n:DResource {storedFilename: "Anna-1364b7d6-d35f-4702-95cc-434e96401df1.tmp", imageFormat: "", filename: "Anna.tmp", id: "bf486f80-3a16-4658-8d89-ab80cb380f22"});
CREATE (n:Descriptor {name: "File", id: "cdde520e-fd87-4062-b6d2-b4c2362afa66", value: "Anna.tmp"});
CREATE (n:Object {position: [], id: "c5b8e420-cf48-4b0c-8969-7858828b74c9"});
CREATE (n:Descriptor {name: "Name", id: "864a971a-25e3-4e66-8c41-d38602e9213e", value: "Ben"});
CREATE (n:DResource {storedFilename: "Ben-8670c4b0-4063-49f7-ad8b-2efc6f239dae.tmp", imageFormat: "", filename: "Ben.tmp", id: "176f78be-237d-4876-be68-966e57772a64"});
CREATE (n:Descriptor {name: "File", id: "1d4faa33-8225-4ff9-9397-e41cade7ce83", value: "Ben.tmp"});
CREATE (n:Object {position: [], id: "ac652759-1ccf-4bc3-b608-fda0e55a72d6"});
CREATE (n:Descriptor {name: "Name", id: "ab0600b4-5f79-4703-9489-a2b77d16df6d", value: "Belgium"});
CREATE (n:Object {position: [], id: "7410defc-0691-4cca-9fb6-3834942ff6e5"});
CREATE (n:Descriptor {name: "Name", id: "a335a8e5-f470-435b-b127-c48a3fd6fb72", value: "Ghent"});
CREATE (n:Object {position: [], id: "27b23549-1297-4a4c-8401-c09b8581685d"});
CREATE (n:Descriptor {name: "Name", id: "50c2b80a-0e76-4fbb-96e8-666a2c8a3e8c", value: "Logs"});
CREATE (n:Object {position: [], id: "c8d75213-2b15-481a-9a30-ebbdfcc2db0f"});
CREATE (n:Descriptor {name: "Name", id: "d7d70ede-7956-4e06-80a8-4a359aaaa94c", value: "Clara"});
CREATE (n:DResource {storedFilename: "Clara-1180fedc-9491-4b44-86de-49bf3537de3f.tmp", imageFormat: "", filename: "Clara.tmp", id: "72c51559-acb7-455e-82bc-dd6ae4a94f86"});
CREATE (n:Descriptor {name: "File", id: "a0c4a01f-eeaf-4767-a9f6-2278c6d3ff1a", value: "Clara.tmp"});
CREATE (n:Object {position: [], id: "4bf1d6f5-b8a0-43d5-a553-c328ab00f2e5"});
CREATE (n:Descriptor {name: "Name", id: "daed7e4e-9b32-431d-a4a4-889cdf5dfb67", value: "Ella"});
CREATE (n:DResource {storedFilename: "Ella-82a2c277-54f2-4cfd-a815-3988ad6e09c2.tmp", imageFormat: "", filename: "Ella.tmp", id: "7ca576de-58bc-438b-af2b-663d22c3d867"});
CREATE (n:Descriptor {name: "File", id: "0cd8a665-8d3a-4fa1-9352-4d986b01910e", value: "Ella.tmp"});
CREATE (n:Object {position: [], id: "277545f9-d8be-4c68-bb73-71f14b23d218"});
CREATE (n:Descriptor {name: "Name", id: "2f49d863-71fa-4e6f-b3df-0a3f62a55064", value: "Lentils"});
CREATE (n:Object {position: [], id: "397f2fcf-1589-42d9-9a67-3598b3264750"});
CREATE (n:Descriptor {name: "Name", id: "da999134-539f-4f73-9276-135dbc0285c5", value: "Emma"});
CREATE (n:DResource {storedFilename: "Emma-8c9a73f7-067a-4c57-b61d-8cd6ced9d748.tmp", imageFormat: "", filename: "Emma.tmp", id: "c0fae25b-c681-4ae9-8060-c128fd62cac7"});
CREATE (n:Descriptor {name: "File", id: "2793f40c-d160-4b4f-90f7-54fd5b1ba07c", value: "Emma.tmp"});
CREATE (n:Object {position: [], id: "2ecdf0e3-b661-4e3f-a697-7ef9bd8b56eb"});
CREATE (n:Descriptor {name: "Name", id: "6f9f9ef0-7269-48ef-81be-3ed73163f23d", value: "Mia"});
CREATE (n:DResource {storedFilename: "Mia-6f3eea34-c80d-4a68-9c1c-f9a5cd577f4b.tmp", imageFormat: "", filename: "Mia.tmp", id: "8eaaa82d-c188-44b1-924b-1803698134ec"});
CREATE (n:Descriptor {name: "File", id: "9a6ad62d-e1de-4e7d-ab8a-8bcda59e7a31", value: "Mia.tmp"});
CREATE (n:Object {position: [], id: "bcc04d0e-e2c7-4a14-b33f-f6f23ce40ea6"});
CREATE (n:Descriptor {name: "Name", id: "cc5ad796-c9ac-4827-8884-10acec5a0a5f", value: "Eva"});
CREATE (n:DResource {storedFilename: "Eva-d59da424-7ac5-4d81-aa61-3b6f471c4bc6.tmp", imageFormat: "", filename: "Eva.tmp", id: "3b471d15-8014-4dc7-8106-63df3a067bbb"});
CREATE (n:Descriptor {name: "File", id: "9efedcb9-aac2-425c-af7c-ad51cc53c08a", value: "Eva.tmp"});
CREATE (n:Object {position: [], id: "e728d9ae-b639-41dc-aaa4-dac8a6267e81"});
CREATE (n:Descriptor {name: "Name", id: "03d978e9-9fa3-4671-b8d2-72bbb6580cd0", value: "Czechoslovakia"});
CREATE (n:Object {position: [], id: "65177d03-1f84-4b27-b7ae-289a88ec983e"});
CREATE (n:Descriptor {name: "Name", id: "4cd9f48d-d664-43b7-acb7-8b0cd77e4b04", value: "Prague"});
CREATE (n:Object {position: [], id: "bf3402c5-4a5b-44b6-9ad2-c8905a271755"});
CREATE (n:Descriptor {name: "Name", id: "d7cc460f-031b-4e8b-ac8e-9a0367176f48", value: "Gothic"});
CREATE (n:Object {position: [], id: "434cf4ed-f3f0-428f-8b4c-cb2b9f614f86"});
CREATE (n:Descriptor {name: "Name", id: "4f8a62d4-2e63-478c-88e4-2d3d2ea08a56", value: "Ian"});
CREATE (n:DResource {storedFilename: "Ian-f979e1e0-7f30-4452-b988-1f72ae232dc2.tmp", imageFormat: "", filename: "Ian.tmp", id: "cda231a2-70b9-4d4d-8db3-5aa610bc27af"});
CREATE (n:Descriptor {name: "File", id: "a9be26e7-8099-460d-909c-cbc8e455aada", value: "Ian.tmp"});
CREATE (n:Object {position: [], id: "8cc4e333-89f6-4099-b0a5-e55ba26fc394"});
CREATE (n:Descriptor {name: "Name", id: "c528807a-a93d-4735-8605-f7c3cfa22ba7", value: "Beta"});
CREATE (n:Object {position: [], id: "3f6f5d51-115e-4424-9e10-c5fc8fd46b2e"});
CREATE (n:Descriptor {name: "Name", id: "03efb464-3e00-4d7a-9b7e-b7c7a1aa0dc5", value: "Julia"});
CREATE (n:DResource {storedFilename: "Julia-ce28674b-d3da-4b4c-b234-67fa24b79937.tmp", imageFormat: "", filename: "Julia.tmp", id: "ec149446-a5ec-410d-9c24-2d2d41709ba9"});
CREATE (n:Descriptor {name: "File", id: "e78f9bc5-97c0-4cf5-b602-bc609ea517d3", value: "Julia.tmp"});
CREATE (n:Object {position: [], id: "433a1421-4396-4d74-988e-6379100d884b"});
CREATE (n:Descriptor {name: "Name", id: "046eded4-e998-4410-b982-e43bbdf99a7e", value: "Leo"});
CREATE (n:DResource {storedFilename: "Leo-afbf38ce-7f8e-460f-bdab-955c046476d6.tmp", imageFormat: "", filename: "Leo.tmp", id: "756bd7d7-654c-4c7f-900f-1c55a4491af4"});
CREATE (n:Descriptor {name: "File", id: "2654cfcb-77e5-4377-8861-0a7aac5e8393", value: "Leo.tmp"});
CREATE (n:Object {position: [], id: "e68e3286-bf37-49bf-aaa1-9bf91592d7c6"});
CREATE (n:Descriptor {name: "Name", id: "7d06f36d-5b6c-43df-bbe3-5a8f9d08f554", value: "Antwerp"});
CREATE (n:Object {position: [], id: "b6367ae3-3a83-402d-9f47-e1a51ba2522d"});
CREATE (n:Descriptor {name: "Name", id: "86c59661-2c8e-4a01-8677-5b704ccf6980", value: "Alpha"});
CREATE (n:Object {position: [], id: "ff9db98a-db77-4c74-a363-84a68bbb5a4c"});
CREATE (n:Descriptor {name: "Name", id: "f06e79e4-4b1a-420b-9d35-b80266b0c81d", value: "Luca"});
CREATE (n:DResource {storedFilename: "Luca-63486790-6867-427b-b739-05fc1e4443bb.tmp", imageFormat: "", filename: "Luca.tmp", id: "8b080c91-8249-4fdf-810c-1956f2b379f7"});
CREATE (n:Descriptor {name: "File", id: "78ce3f08-99f9-49b7-a498-6a4f85610357", value: "Luca.tmp"});
CREATE (n:Object {position: [], id: "57043c13-a9ea-4c4b-be7b-ad86287ec8f7"});
CREATE (n:Descriptor {name: "Name", id: "79cfc0e8-f3d4-4537-9c67-c99acffc9490", value: "Automobiles"});
CREATE (n:Object {position: [], id: "9639d13a-b650-497d-80dd-1c3058339f75"});
CREATE (n:Descriptor {name: "Name", id: "2be19da5-9b18-4df5-99c1-dd4a615dd49b", value: "Mark"});
CREATE (n:DResource {storedFilename: "Mark-74c075f4-7b3e-41e0-aaf8-93799f8247ec.tmp", imageFormat: "", filename: "Mark.tmp", id: "4b402e81-e323-4a7c-900f-fc08b6c14456"});
CREATE (n:Descriptor {name: "File", id: "c4a7e2ed-30d0-4648-8050-7943e37f43a2", value: "Mark.tmp"});
CREATE (n:Object {position: [], id: "72d29de1-8a42-425f-a193-31557513e4d9"});
CREATE (n:Descriptor {name: "Name", id: "91a9cd16-15c7-45ff-9325-734691e7e7a6", value: "Max"});
CREATE (n:DResource {storedFilename: "Max-64c51b2c-a434-44c7-b74e-ffb6c4ad70fe.tmp", imageFormat: "", filename: "Max.tmp", id: "21c56af5-f81e-4bf1-8f6f-9114d97aeb0b"});
CREATE (n:Descriptor {name: "File", id: "cc475ca8-64f3-4457-acff-90401a5be76e", value: "Max.tmp"});
CREATE (n:Object {position: [], id: "1e559a30-f561-4ba8-90b2-c1a81a4fda8c"});
CREATE (n:Descriptor {name: "Name", id: "06eaf2fd-c931-4184-84f0-82a811d51abb", value: "Pilsner"});
CREATE (n:Object {position: [], id: "1c59efb0-9029-433f-8b15-96da7e831e8d"});
CREATE (n:Descriptor {name: "Name", id: "e32b3314-6de9-4f22-b860-cac00efc9d13", value: "Nora"});
CREATE (n:DResource {storedFilename: "Nora-5c2ffe1f-5799-4cd3-adb4-fc8c12f7e7b6.tmp", imageFormat: "", filename: "Nora.tmp", id: "3fbfc160-96f2-4f5c-a78e-b5fbeb50ec1a"});
CREATE (n:Descriptor {name: "File", id: "a155a498-e179-4f3a-862b-b4824c502a04", value: "Nora.tmp"});
CREATE (n:Object {position: [], id: "6fd2af8e-ccf1-4ec0-a1eb-c10c17e8e32a"});
CREATE (n:Descriptor {name: "Name", id: "82047454-fec8-4a17-9521-8b93557c539a", value: "Papermill"});
CREATE (n:Object {position: [], id: "7c029791-ab67-405b-8ccf-a2a4c0534efd"});
CREATE (n:Descriptor {name: "Name", id: "48eaf871-f086-45f0-a74c-74583baae9ee", value: "Paul"});
CREATE (n:DResource {storedFilename: "Paul-f2cd35cf-35ba-455d-9fb6-4858c992fc9a.tmp", imageFormat: "", filename: "Paul.tmp", id: "c8064c8a-5b7c-4c01-b939-38ec270d8d3a"});
CREATE (n:Descriptor {name: "File", id: "447751ee-3432-47a0-9551-15115cd680fd", value: "Paul.tmp"});
CREATE (n:Object {position: [], id: "8c93c2f1-82c3-4d38-8d09-f4ad3e4e3228"});
CREATE (n:Descriptor {name: "Name", id: "d6ea2c44-9181-4681-8791-4c8d104c5718", value: "Rita"});
CREATE (n:DResource {storedFilename: "Rita-9ea6e68f-2714-4907-9a29-e2c0182c1c33.tmp", imageFormat: "", filename: "Rita.tmp", id: "07d680ed-1942-4ef9-89bb-d9bdf824f88a"});
CREATE (n:Descriptor {name: "File", id: "604a14e3-5376-421f-aeb3-2d99a1b31a54", value: "Rita.tmp"});
CREATE (n:Object {position: [], id: "6b025658-69c0-45d1-adba-1aba354600e5"});
CREATE (n:Descriptor {name: "Name", id: "d06e3509-26ae-4c08-9a5b-ea812d459470", value: "Bratislava"});
CREATE (n:Object {position: [], id: "a6407575-9260-4045-9dad-ebfa2f74a47f"});
CREATE (n:Descriptor {name: "Name", id: "f69dc393-a127-4ab5-ad18-7f2af316b993", value: "Sara"});
CREATE (n:DResource {storedFilename: "Sara-84c88644-ad3f-4297-9862-841986bf94b2.tmp", imageFormat: "", filename: "Sara.tmp", id: "828a8a68-4114-4441-9d42-78fadeaa4994"});
CREATE (n:Descriptor {name: "File", id: "c2fd6954-4fa1-46ec-b53e-73f4fa818037", value: "Sara.tmp"});
CREATE (n:Object {position: [], id: "15da7a00-3068-48ea-ad1a-da7a22e31ef4"});
CREATE (n:Descriptor {name: "Name", id: "5a4022e9-5296-4619-a5b0-2d29ef381ddf", value: "Simon"});
CREATE (n:DResource {storedFilename: "Simon-ff4770ea-bdf7-4719-9235-ee747b9a6202.tmp", imageFormat: "", filename: "Simon.tmp", id: "e73b160f-6ec6-45ae-b1e3-2e31078f6715"});
CREATE (n:Descriptor {name: "File", id: "6964fc43-c18b-48d7-a606-3e3a0ac36cd1", value: "Simon.tmp"});
CREATE (n:Object {position: [], id: "1ad4e3d1-d5d8-44d6-b517-d3516f6f615b"});
CREATE (n:Descriptor {name: "Name", id: "8012f1b8-7e48-4451-bdcc-d1773ec66a4c", value: "Bakery"});
CREATE (n:Object {position: [], id: "69311012-c6ba-416f-8ea2-ba14f881d8ed"});
CREATE (n:Descriptor {name: "Name", id: "62b41726-4e1c-4d56-8351-86b1809634b8", value: "Tom"});
CREATE (n:DResource {storedFilename: "Tom-0819792b-7793-4ee0-bb8e-c9166240bc8b.tmp", imageFormat: "", filename: "Tom.tmp", id: "3ecda20f-fd88-4d80-a5d1-ce7e816e3d8a"});
CREATE (n:Descriptor {name: "File", id: "8c6abd44-0b68-4e58-acf8-3ba679dd072a", value: "Tom.tmp"});
CREATE (n:Object {position: [], id: "b23d313f-1554-4fb5-9ba0-b11d39465644"});
CREATE (n:Descriptor {name: "Name", id: "651273f9-1dd1-4057-b91f-c8714f24322d", value: "Zoe"});
CREATE (n:DResource {storedFilename: "Zoe-38ff5883-2966-446e-bdb3-7794922a9968.tmp", imageFormat: "", filename: "Zoe.tmp", id: "264754b5-079e-42e5-9cc7-6b51873c8118"});
CREATE (n:Descriptor {name: "File", id: "58fe2d58-47d4-42e1-b0ca-844b1b498ad3", value: "Zoe.tmp"});
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "15f481db-cb1e-439e-bb36-624f7b8954e3"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "aaf4a42e-2b85-4b78-b216-18934453a10d"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "15f481db-cb1e-439e-bb36-624f7b8954e3" AND b.id = "aaf4a42e-2b85-4b78-b216-18934453a10d"
CREATE (a)-[:MEMBER_OF {id: "9ea80ffa-356d-4635-b96d-258e5124056a"}]->(b);
MATCH (a), (b) WHERE a.id = "aaf4a42e-2b85-4b78-b216-18934453a10d" AND b.id = "9286c010-be4c-49d6-a053-3aaefdbcdd39"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "15f481db-cb1e-439e-bb36-624f7b8954e3" AND b.id = "b211e841-aea8-4338-9b02-982132a74f05"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "7199db51-14a6-4888-9ea8-cd4d6712463a"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "b4375feb-f434-450b-be3e-efe3fb33fed4"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "7199db51-14a6-4888-9ea8-cd4d6712463a" AND b.id = "b4375feb-f434-450b-be3e-efe3fb33fed4"
CREATE (a)-[:MEMBER_OF {id: "dcf18c32-920c-40dd-ae3e-822e7e7ba10e"}]->(b);
MATCH (a), (b) WHERE a.id = "b4375feb-f434-450b-be3e-efe3fb33fed4" AND b.id = "36c5585c-b402-4a40-a76a-9663cb669ebe"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7199db51-14a6-4888-9ea8-cd4d6712463a" AND b.id = "cb20968c-9034-487b-8777-1b8889ee2beb"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "18a0b15b-b64f-47c7-b422-5a9c990b4c2a"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "08eb8ea7-fe4d-4d67-8a39-a93c6b1e1005"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "18a0b15b-b64f-47c7-b422-5a9c990b4c2a" AND b.id = "08eb8ea7-fe4d-4d67-8a39-a93c6b1e1005"
CREATE (a)-[:MEMBER_OF {id: "17141857-8309-4871-a1b9-9be6b5758188"}]->(b);
MATCH (a), (b) WHERE a.id = "08eb8ea7-fe4d-4d67-8a39-a93c6b1e1005" AND b.id = "0b0e8bc2-fd3a-49d4-b7b1-e5ad6776c813"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "18a0b15b-b64f-47c7-b422-5a9c990b4c2a" AND b.id = "64008966-9d76-4ce0-9025-54754d000b92"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "075b257d-6ec7-42a7-9dc0-704e0e6d9723"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "075b257d-6ec7-42a7-9dc0-704e0e6d9723" AND b.id = "08eb8ea7-fe4d-4d67-8a39-a93c6b1e1005"
CREATE (a)-[:MEMBER_OF {id: "485c3cd6-bf74-4a5a-b897-d426dde30443"}]->(b);
MATCH (a), (b) WHERE a.id = "075b257d-6ec7-42a7-9dc0-704e0e6d9723" AND b.id = "4d2001ca-c007-4fc6-9d27-8d85741020f4"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "a1f4e795-feb6-4157-bb82-05b4bcef3f41"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "a1f4e795-feb6-4157-bb82-05b4bcef3f41" AND b.id = "08eb8ea7-fe4d-4d67-8a39-a93c6b1e1005"
CREATE (a)-[:MEMBER_OF {id: "93e96677-540b-4d3c-9bd6-aee93ecd7225"}]->(b);
MATCH (a), (b) WHERE a.id = "a1f4e795-feb6-4157-bb82-05b4bcef3f41" AND b.id = "9e2644c6-8bc1-495d-b09f-c80fae087b6d"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "23183af4-f37e-4d54-a6b9-3d26793b1182"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "23183af4-f37e-4d54-a6b9-3d26793b1182" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "74f2f846-9ff0-4ec5-9217-d5a30bd87346"}]->(b);
MATCH (a), (b) WHERE a.id = "1aea3d83-156c-4df9-82b2-d0504312871d" AND b.id = "34561d5f-1d5f-4307-b91b-5d7630b1916a"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "23183af4-f37e-4d54-a6b9-3d26793b1182" AND b.id = "81f4b822-0ef4-49f7-897d-d5fb9a171b85"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "1aea3d83-156c-4df9-82b2-d0504312871d" AND b.id = "931f7c41-ca75-4601-a7ee-bd881d22ed9a"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "23183af4-f37e-4d54-a6b9-3d26793b1182" AND b.id = "cd161fd6-0586-4f8e-bf42-117e26727e3e"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "cd161fd6-0586-4f8e-bf42-117e26727e3e" AND b.id = "5a054c3d-31d7-44b9-9c3a-14374c678f10"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "190673e3-f115-4455-ac30-59ce14b4098c"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "190673e3-f115-4455-ac30-59ce14b4098c" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "0c33d1b6-a005-4aea-9292-e8fe3fe1dec0"}]->(b);
MATCH (a), (b) WHERE a.id = "190673e3-f115-4455-ac30-59ce14b4098c" AND b.id = "5d3222a3-d8d6-40c2-ae94-d980e375e711"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "190673e3-f115-4455-ac30-59ce14b4098c" AND b.id = "4e506f0c-c0fe-40e4-89b4-a42d5dbd3b90"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "4e506f0c-c0fe-40e4-89b4-a42d5dbd3b90" AND b.id = "1c880c36-de6f-47e6-a5bf-d73e1e9e8429"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "9127f425-a6be-471a-b60d-85c041614d33"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "9127f425-a6be-471a-b60d-85c041614d33" AND b.id = "b4375feb-f434-450b-be3e-efe3fb33fed4"
CREATE (a)-[:MEMBER_OF {id: "d069e4a9-902e-4c91-824a-87598b9cc9e2"}]->(b);
MATCH (a), (b) WHERE a.id = "9127f425-a6be-471a-b60d-85c041614d33" AND b.id = "9481a3ee-ce51-4b06-aa6f-42880b153e12"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "14069664-71bb-4896-9162-b3f50c6c21b9"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "14069664-71bb-4896-9162-b3f50c6c21b9" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "73053d7b-95b0-414b-9f50-2cdd3076cdfc"}]->(b);
MATCH (a), (b) WHERE a.id = "14069664-71bb-4896-9162-b3f50c6c21b9" AND b.id = "c8b56754-50ad-4306-a90c-0aa30e809a63"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "14069664-71bb-4896-9162-b3f50c6c21b9" AND b.id = "bb86d1f0-faa5-4027-a438-a1210a411e3a"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "bb86d1f0-faa5-4027-a438-a1210a411e3a" AND b.id = "c4c70068-9bf2-4285-af91-ebc5104389d5"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "ff34dcff-0db4-461c-b49a-1c2c2f040a86"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "ff34dcff-0db4-461c-b49a-1c2c2f040a86" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "79689ca0-0c7d-4083-bc68-beb82d7651c4"}]->(b);
MATCH (a), (b) WHERE a.id = "ff34dcff-0db4-461c-b49a-1c2c2f040a86" AND b.id = "cea2a3c6-779d-440c-a2aa-4c0a81cc9873"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "ff34dcff-0db4-461c-b49a-1c2c2f040a86" AND b.id = "63effc4f-7e57-4d85-b30b-5fbd1dd9abea"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "63effc4f-7e57-4d85-b30b-5fbd1dd9abea" AND b.id = "e51a47da-ca2a-4b15-8865-48c66b99c963"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "9a58f942-77fa-45cb-b64f-8ef122f2b6ae"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "9a58f942-77fa-45cb-b64f-8ef122f2b6ae" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "62a9113b-c40c-458b-9259-dc607eb7b69b"}]->(b);
MATCH (a), (b) WHERE a.id = "9a58f942-77fa-45cb-b64f-8ef122f2b6ae" AND b.id = "b36220b3-f9b1-4ca7-8843-81d300760773"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "9a58f942-77fa-45cb-b64f-8ef122f2b6ae" AND b.id = "cdde520e-fd87-4062-b6d2-b4c2362afa66"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "cdde520e-fd87-4062-b6d2-b4c2362afa66" AND b.id = "bf486f80-3a16-4658-8d89-ab80cb380f22"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "c5b8e420-cf48-4b0c-8969-7858828b74c9"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "c5b8e420-cf48-4b0c-8969-7858828b74c9" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "979fd559-4b3b-46e7-8f2a-96082758743c"}]->(b);
MATCH (a), (b) WHERE a.id = "c5b8e420-cf48-4b0c-8969-7858828b74c9" AND b.id = "864a971a-25e3-4e66-8c41-d38602e9213e"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "c5b8e420-cf48-4b0c-8969-7858828b74c9" AND b.id = "1d4faa33-8225-4ff9-9397-e41cade7ce83"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "1d4faa33-8225-4ff9-9397-e41cade7ce83" AND b.id = "176f78be-237d-4876-be68-966e57772a64"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "ac652759-1ccf-4bc3-b608-fda0e55a72d6"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "ac652759-1ccf-4bc3-b608-fda0e55a72d6" AND b.id = "aaf4a42e-2b85-4b78-b216-18934453a10d"
CREATE (a)-[:MEMBER_OF {id: "22402c9e-5528-433c-b69a-acfd96811f77"}]->(b);
MATCH (a), (b) WHERE a.id = "ac652759-1ccf-4bc3-b608-fda0e55a72d6" AND b.id = "ab0600b4-5f79-4703-9489-a2b77d16df6d"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "7410defc-0691-4cca-9fb6-3834942ff6e5"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "7410defc-0691-4cca-9fb6-3834942ff6e5" AND b.id = "b4375feb-f434-450b-be3e-efe3fb33fed4"
CREATE (a)-[:MEMBER_OF {id: "533af752-3850-4d63-9997-e5dafa7b74ee"}]->(b);
MATCH (a), (b) WHERE a.id = "7410defc-0691-4cca-9fb6-3834942ff6e5" AND b.id = "a335a8e5-f470-435b-b127-c48a3fd6fb72"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "27b23549-1297-4a4c-8401-c09b8581685d"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "27b23549-1297-4a4c-8401-c09b8581685d" AND b.id = "08eb8ea7-fe4d-4d67-8a39-a93c6b1e1005"
CREATE (a)-[:MEMBER_OF {id: "d796effb-9b45-4929-a889-126c04eb2be2"}]->(b);
MATCH (a), (b) WHERE a.id = "27b23549-1297-4a4c-8401-c09b8581685d" AND b.id = "50c2b80a-0e76-4fbb-96e8-666a2c8a3e8c"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "c8d75213-2b15-481a-9a30-ebbdfcc2db0f"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "c8d75213-2b15-481a-9a30-ebbdfcc2db0f" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "8f24602f-611b-4bd5-8980-a1fbaf3fbcb0"}]->(b);
MATCH (a), (b) WHERE a.id = "c8d75213-2b15-481a-9a30-ebbdfcc2db0f" AND b.id = "d7d70ede-7956-4e06-80a8-4a359aaaa94c"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "c8d75213-2b15-481a-9a30-ebbdfcc2db0f" AND b.id = "a0c4a01f-eeaf-4767-a9f6-2278c6d3ff1a"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "a0c4a01f-eeaf-4767-a9f6-2278c6d3ff1a" AND b.id = "72c51559-acb7-455e-82bc-dd6ae4a94f86"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "4bf1d6f5-b8a0-43d5-a553-c328ab00f2e5"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "4bf1d6f5-b8a0-43d5-a553-c328ab00f2e5" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "c1378710-ed4e-4fbb-9886-0baaa0fc5e11"}]->(b);
MATCH (a), (b) WHERE a.id = "4bf1d6f5-b8a0-43d5-a553-c328ab00f2e5" AND b.id = "daed7e4e-9b32-431d-a4a4-889cdf5dfb67"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "4bf1d6f5-b8a0-43d5-a553-c328ab00f2e5" AND b.id = "0cd8a665-8d3a-4fa1-9352-4d986b01910e"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "0cd8a665-8d3a-4fa1-9352-4d986b01910e" AND b.id = "7ca576de-58bc-438b-af2b-663d22c3d867"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "277545f9-d8be-4c68-bb73-71f14b23d218"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "277545f9-d8be-4c68-bb73-71f14b23d218" AND b.id = "08eb8ea7-fe4d-4d67-8a39-a93c6b1e1005"
CREATE (a)-[:MEMBER_OF {id: "ee717137-613e-434d-8708-1bde2041de2c"}]->(b);
MATCH (a), (b) WHERE a.id = "277545f9-d8be-4c68-bb73-71f14b23d218" AND b.id = "2f49d863-71fa-4e6f-b3df-0a3f62a55064"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "397f2fcf-1589-42d9-9a67-3598b3264750"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "397f2fcf-1589-42d9-9a67-3598b3264750" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "ddbe60fd-4830-44a6-adf8-9373d32c406d"}]->(b);
MATCH (a), (b) WHERE a.id = "397f2fcf-1589-42d9-9a67-3598b3264750" AND b.id = "da999134-539f-4f73-9276-135dbc0285c5"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "397f2fcf-1589-42d9-9a67-3598b3264750" AND b.id = "2793f40c-d160-4b4f-90f7-54fd5b1ba07c"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "2793f40c-d160-4b4f-90f7-54fd5b1ba07c" AND b.id = "c0fae25b-c681-4ae9-8060-c128fd62cac7"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "2ecdf0e3-b661-4e3f-a697-7ef9bd8b56eb"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "2ecdf0e3-b661-4e3f-a697-7ef9bd8b56eb" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "a7f42e77-9dd7-4e79-be05-d8c0a6d5d8d8"}]->(b);
MATCH (a), (b) WHERE a.id = "2ecdf0e3-b661-4e3f-a697-7ef9bd8b56eb" AND b.id = "6f9f9ef0-7269-48ef-81be-3ed73163f23d"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "2ecdf0e3-b661-4e3f-a697-7ef9bd8b56eb" AND b.id = "9a6ad62d-e1de-4e7d-ab8a-8bcda59e7a31"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "9a6ad62d-e1de-4e7d-ab8a-8bcda59e7a31" AND b.id = "8eaaa82d-c188-44b1-924b-1803698134ec"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "bcc04d0e-e2c7-4a14-b33f-f6f23ce40ea6"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "bcc04d0e-e2c7-4a14-b33f-f6f23ce40ea6" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "3c00d67a-41ca-4609-b275-ed0ba8630f8a"}]->(b);
MATCH (a), (b) WHERE a.id = "bcc04d0e-e2c7-4a14-b33f-f6f23ce40ea6" AND b.id = "cc5ad796-c9ac-4827-8884-10acec5a0a5f"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "bcc04d0e-e2c7-4a14-b33f-f6f23ce40ea6" AND b.id = "9efedcb9-aac2-425c-af7c-ad51cc53c08a"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "9efedcb9-aac2-425c-af7c-ad51cc53c08a" AND b.id = "3b471d15-8014-4dc7-8106-63df3a067bbb"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "e728d9ae-b639-41dc-aaa4-dac8a6267e81"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "e728d9ae-b639-41dc-aaa4-dac8a6267e81" AND b.id = "aaf4a42e-2b85-4b78-b216-18934453a10d"
CREATE (a)-[:MEMBER_OF {id: "09fdc114-a79c-4894-9b32-99511f835590"}]->(b);
MATCH (a), (b) WHERE a.id = "e728d9ae-b639-41dc-aaa4-dac8a6267e81" AND b.id = "03d978e9-9fa3-4671-b8d2-72bbb6580cd0"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "65177d03-1f84-4b27-b7ae-289a88ec983e"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "65177d03-1f84-4b27-b7ae-289a88ec983e" AND b.id = "b4375feb-f434-450b-be3e-efe3fb33fed4"
CREATE (a)-[:MEMBER_OF {id: "f74dc68f-2335-4cce-899b-c371f5a2c320"}]->(b);
MATCH (a), (b) WHERE a.id = "65177d03-1f84-4b27-b7ae-289a88ec983e" AND b.id = "4cd9f48d-d664-43b7-acb7-8b0cd77e4b04"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "bf3402c5-4a5b-44b6-9ad2-c8905a271755"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "bf3402c5-4a5b-44b6-9ad2-c8905a271755" AND b.id = "08eb8ea7-fe4d-4d67-8a39-a93c6b1e1005"
CREATE (a)-[:MEMBER_OF {id: "c3db8e84-3777-4c0c-8060-af42cfbf1e08"}]->(b);
MATCH (a), (b) WHERE a.id = "bf3402c5-4a5b-44b6-9ad2-c8905a271755" AND b.id = "d7cc460f-031b-4e8b-ac8e-9a0367176f48"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "434cf4ed-f3f0-428f-8b4c-cb2b9f614f86"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "434cf4ed-f3f0-428f-8b4c-cb2b9f614f86" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "0d8e2f37-f5c0-4297-a53b-34b00f485250"}]->(b);
MATCH (a), (b) WHERE a.id = "434cf4ed-f3f0-428f-8b4c-cb2b9f614f86" AND b.id = "4f8a62d4-2e63-478c-88e4-2d3d2ea08a56"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "434cf4ed-f3f0-428f-8b4c-cb2b9f614f86" AND b.id = "a9be26e7-8099-460d-909c-cbc8e455aada"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "a9be26e7-8099-460d-909c-cbc8e455aada" AND b.id = "cda231a2-70b9-4d4d-8db3-5aa610bc27af"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "8cc4e333-89f6-4099-b0a5-e55ba26fc394"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "8cc4e333-89f6-4099-b0a5-e55ba26fc394" AND b.id = "08eb8ea7-fe4d-4d67-8a39-a93c6b1e1005"
CREATE (a)-[:MEMBER_OF {id: "52316655-0de9-4622-b470-7f404402ee0e"}]->(b);
MATCH (a), (b) WHERE a.id = "8cc4e333-89f6-4099-b0a5-e55ba26fc394" AND b.id = "c528807a-a93d-4735-8605-f7c3cfa22ba7"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "3f6f5d51-115e-4424-9e10-c5fc8fd46b2e"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "3f6f5d51-115e-4424-9e10-c5fc8fd46b2e" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "6214d0ea-a20c-4a12-bd3d-22e748c4a02b"}]->(b);
MATCH (a), (b) WHERE a.id = "3f6f5d51-115e-4424-9e10-c5fc8fd46b2e" AND b.id = "03efb464-3e00-4d7a-9b7e-b7c7a1aa0dc5"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "3f6f5d51-115e-4424-9e10-c5fc8fd46b2e" AND b.id = "e78f9bc5-97c0-4cf5-b602-bc609ea517d3"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "e78f9bc5-97c0-4cf5-b602-bc609ea517d3" AND b.id = "ec149446-a5ec-410d-9c24-2d2d41709ba9"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "433a1421-4396-4d74-988e-6379100d884b"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "433a1421-4396-4d74-988e-6379100d884b" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "14718dec-fca8-4d32-bd73-428ce5cae068"}]->(b);
MATCH (a), (b) WHERE a.id = "433a1421-4396-4d74-988e-6379100d884b" AND b.id = "046eded4-e998-4410-b982-e43bbdf99a7e"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "433a1421-4396-4d74-988e-6379100d884b" AND b.id = "2654cfcb-77e5-4377-8861-0a7aac5e8393"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "2654cfcb-77e5-4377-8861-0a7aac5e8393" AND b.id = "756bd7d7-654c-4c7f-900f-1c55a4491af4"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "e68e3286-bf37-49bf-aaa1-9bf91592d7c6"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "e68e3286-bf37-49bf-aaa1-9bf91592d7c6" AND b.id = "b4375feb-f434-450b-be3e-efe3fb33fed4"
CREATE (a)-[:MEMBER_OF {id: "b45b6f7d-61f0-4366-9942-60b9c8ebd979"}]->(b);
MATCH (a), (b) WHERE a.id = "e68e3286-bf37-49bf-aaa1-9bf91592d7c6" AND b.id = "7d06f36d-5b6c-43df-bbe3-5a8f9d08f554"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "b6367ae3-3a83-402d-9f47-e1a51ba2522d"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "b6367ae3-3a83-402d-9f47-e1a51ba2522d" AND b.id = "08eb8ea7-fe4d-4d67-8a39-a93c6b1e1005"
CREATE (a)-[:MEMBER_OF {id: "4ea5ad7d-0ba5-49cf-8791-cdc7c1289429"}]->(b);
MATCH (a), (b) WHERE a.id = "b6367ae3-3a83-402d-9f47-e1a51ba2522d" AND b.id = "86c59661-2c8e-4a01-8677-5b704ccf6980"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "ff9db98a-db77-4c74-a363-84a68bbb5a4c"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "ff9db98a-db77-4c74-a363-84a68bbb5a4c" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "d3b4cd98-4bd6-42cd-a773-319aa140fd0c"}]->(b);
MATCH (a), (b) WHERE a.id = "ff9db98a-db77-4c74-a363-84a68bbb5a4c" AND b.id = "f06e79e4-4b1a-420b-9d35-b80266b0c81d"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "ff9db98a-db77-4c74-a363-84a68bbb5a4c" AND b.id = "78ce3f08-99f9-49b7-a498-6a4f85610357"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "78ce3f08-99f9-49b7-a498-6a4f85610357" AND b.id = "8b080c91-8249-4fdf-810c-1956f2b379f7"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "57043c13-a9ea-4c4b-be7b-ad86287ec8f7"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "57043c13-a9ea-4c4b-be7b-ad86287ec8f7" AND b.id = "08eb8ea7-fe4d-4d67-8a39-a93c6b1e1005"
CREATE (a)-[:MEMBER_OF {id: "904be5c6-9412-46df-aa3c-e7b5201f16d3"}]->(b);
MATCH (a), (b) WHERE a.id = "57043c13-a9ea-4c4b-be7b-ad86287ec8f7" AND b.id = "79cfc0e8-f3d4-4537-9c67-c99acffc9490"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "9639d13a-b650-497d-80dd-1c3058339f75"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "9639d13a-b650-497d-80dd-1c3058339f75" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "b772e775-2c39-4b4e-bd85-6ad2852158ae"}]->(b);
MATCH (a), (b) WHERE a.id = "9639d13a-b650-497d-80dd-1c3058339f75" AND b.id = "2be19da5-9b18-4df5-99c1-dd4a615dd49b"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "9639d13a-b650-497d-80dd-1c3058339f75" AND b.id = "c4a7e2ed-30d0-4648-8050-7943e37f43a2"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "c4a7e2ed-30d0-4648-8050-7943e37f43a2" AND b.id = "4b402e81-e323-4a7c-900f-fc08b6c14456"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "72d29de1-8a42-425f-a193-31557513e4d9"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "72d29de1-8a42-425f-a193-31557513e4d9" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "3b8433fd-c6f7-4465-9d89-2454be2e7af8"}]->(b);
MATCH (a), (b) WHERE a.id = "72d29de1-8a42-425f-a193-31557513e4d9" AND b.id = "91a9cd16-15c7-45ff-9325-734691e7e7a6"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "72d29de1-8a42-425f-a193-31557513e4d9" AND b.id = "cc475ca8-64f3-4457-acff-90401a5be76e"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "cc475ca8-64f3-4457-acff-90401a5be76e" AND b.id = "21c56af5-f81e-4bf1-8f6f-9114d97aeb0b"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "1e559a30-f561-4ba8-90b2-c1a81a4fda8c"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "1e559a30-f561-4ba8-90b2-c1a81a4fda8c" AND b.id = "08eb8ea7-fe4d-4d67-8a39-a93c6b1e1005"
CREATE (a)-[:MEMBER_OF {id: "ee6f7210-0317-4465-9028-58ef14568dc9"}]->(b);
MATCH (a), (b) WHERE a.id = "1e559a30-f561-4ba8-90b2-c1a81a4fda8c" AND b.id = "06eaf2fd-c931-4184-84f0-82a811d51abb"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "1c59efb0-9029-433f-8b15-96da7e831e8d"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "1c59efb0-9029-433f-8b15-96da7e831e8d" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "004fc3bb-306d-4c53-90d9-5fa0423c4c5a"}]->(b);
MATCH (a), (b) WHERE a.id = "1c59efb0-9029-433f-8b15-96da7e831e8d" AND b.id = "e32b3314-6de9-4f22-b860-cac00efc9d13"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "1c59efb0-9029-433f-8b15-96da7e831e8d" AND b.id = "a155a498-e179-4f3a-862b-b4824c502a04"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "a155a498-e179-4f3a-862b-b4824c502a04" AND b.id = "3fbfc160-96f2-4f5c-a78e-b5fbeb50ec1a"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "6fd2af8e-ccf1-4ec0-a1eb-c10c17e8e32a"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "6fd2af8e-ccf1-4ec0-a1eb-c10c17e8e32a" AND b.id = "08eb8ea7-fe4d-4d67-8a39-a93c6b1e1005"
CREATE (a)-[:MEMBER_OF {id: "ea3f1fa8-d51c-47ff-9df6-f62bea2a8adc"}]->(b);
MATCH (a), (b) WHERE a.id = "6fd2af8e-ccf1-4ec0-a1eb-c10c17e8e32a" AND b.id = "82047454-fec8-4a17-9521-8b93557c539a"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "7c029791-ab67-405b-8ccf-a2a4c0534efd"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "7c029791-ab67-405b-8ccf-a2a4c0534efd" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "83b35575-f870-4601-b469-3f4aa6c6180f"}]->(b);
MATCH (a), (b) WHERE a.id = "7c029791-ab67-405b-8ccf-a2a4c0534efd" AND b.id = "48eaf871-f086-45f0-a74c-74583baae9ee"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7c029791-ab67-405b-8ccf-a2a4c0534efd" AND b.id = "447751ee-3432-47a0-9551-15115cd680fd"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "447751ee-3432-47a0-9551-15115cd680fd" AND b.id = "c8064c8a-5b7c-4c01-b939-38ec270d8d3a"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "8c93c2f1-82c3-4d38-8d09-f4ad3e4e3228"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "8c93c2f1-82c3-4d38-8d09-f4ad3e4e3228" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "2b48fdba-ac53-4758-a0bd-8d15baa770f1"}]->(b);
MATCH (a), (b) WHERE a.id = "8c93c2f1-82c3-4d38-8d09-f4ad3e4e3228" AND b.id = "d6ea2c44-9181-4681-8791-4c8d104c5718"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "8c93c2f1-82c3-4d38-8d09-f4ad3e4e3228" AND b.id = "604a14e3-5376-421f-aeb3-2d99a1b31a54"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "604a14e3-5376-421f-aeb3-2d99a1b31a54" AND b.id = "07d680ed-1942-4ef9-89bb-d9bdf824f88a"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "6b025658-69c0-45d1-adba-1aba354600e5"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "6b025658-69c0-45d1-adba-1aba354600e5" AND b.id = "b4375feb-f434-450b-be3e-efe3fb33fed4"
CREATE (a)-[:MEMBER_OF {id: "5b78d6a7-eb47-4677-8738-097e2370c2aa"}]->(b);
MATCH (a), (b) WHERE a.id = "6b025658-69c0-45d1-adba-1aba354600e5" AND b.id = "d06e3509-26ae-4c08-9a5b-ea812d459470"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "a6407575-9260-4045-9dad-ebfa2f74a47f"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "a6407575-9260-4045-9dad-ebfa2f74a47f" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "f44b4322-b38d-41df-9bff-17adb4980cb8"}]->(b);
MATCH (a), (b) WHERE a.id = "a6407575-9260-4045-9dad-ebfa2f74a47f" AND b.id = "f69dc393-a127-4ab5-ad18-7f2af316b993"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "a6407575-9260-4045-9dad-ebfa2f74a47f" AND b.id = "c2fd6954-4fa1-46ec-b53e-73f4fa818037"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "c2fd6954-4fa1-46ec-b53e-73f4fa818037" AND b.id = "828a8a68-4114-4441-9d42-78fadeaa4994"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "15da7a00-3068-48ea-ad1a-da7a22e31ef4"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "15da7a00-3068-48ea-ad1a-da7a22e31ef4" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "42835997-0055-48f6-836f-bdbbf6455861"}]->(b);
MATCH (a), (b) WHERE a.id = "15da7a00-3068-48ea-ad1a-da7a22e31ef4" AND b.id = "5a4022e9-5296-4619-a5b0-2d29ef381ddf"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "15da7a00-3068-48ea-ad1a-da7a22e31ef4" AND b.id = "6964fc43-c18b-48d7-a606-3e3a0ac36cd1"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "6964fc43-c18b-48d7-a606-3e3a0ac36cd1" AND b.id = "e73b160f-6ec6-45ae-b1e3-2e31078f6715"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "1ad4e3d1-d5d8-44d6-b517-d3516f6f615b"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "1ad4e3d1-d5d8-44d6-b517-d3516f6f615b" AND b.id = "08eb8ea7-fe4d-4d67-8a39-a93c6b1e1005"
CREATE (a)-[:MEMBER_OF {id: "d4130c96-45bc-4b12-b4b5-d80b588dd663"}]->(b);
MATCH (a), (b) WHERE a.id = "1ad4e3d1-d5d8-44d6-b517-d3516f6f615b" AND b.id = "8012f1b8-7e48-4451-bdcc-d1773ec66a4c"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "69311012-c6ba-416f-8ea2-ba14f881d8ed"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "69311012-c6ba-416f-8ea2-ba14f881d8ed" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "446ef7d2-9cbd-40b2-9396-75f16d6e4788"}]->(b);
MATCH (a), (b) WHERE a.id = "69311012-c6ba-416f-8ea2-ba14f881d8ed" AND b.id = "62b41726-4e1c-4d56-8351-86b1809634b8"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "69311012-c6ba-416f-8ea2-ba14f881d8ed" AND b.id = "8c6abd44-0b68-4e58-acf8-3ba679dd072a"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "8c6abd44-0b68-4e58-acf8-3ba679dd072a" AND b.id = "3ecda20f-fd88-4d80-a5d1-ce7e816e3d8a"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "219c2bbb-985f-4dfd-9425-a70450f7961c" AND b.id = "b23d313f-1554-4fb5-9ba0-b11d39465644"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "b23d313f-1554-4fb5-9ba0-b11d39465644" AND b.id = "1aea3d83-156c-4df9-82b2-d0504312871d"
CREATE (a)-[:MEMBER_OF {id: "97755476-124b-4f77-a77d-79cc9d305b51"}]->(b);
MATCH (a), (b) WHERE a.id = "b23d313f-1554-4fb5-9ba0-b11d39465644" AND b.id = "651273f9-1dd1-4057-b91f-c8714f24322d"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "b23d313f-1554-4fb5-9ba0-b11d39465644" AND b.id = "58fe2d58-47d4-42e1-b0ca-844b1b498ad3"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "58fe2d58-47d4-42e1-b0ca-844b1b498ad3" AND b.id = "264754b5-079e-42e5-9cc7-6b51873c8118"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "aaf4a42e-2b85-4b78-b216-18934453a10d" AND b.id = "b4375feb-f434-450b-be3e-efe3fb33fed4"
CREATE (a)-[:RELATED_TO {comment: "", label: "contains", id: "db0ee9f8-486a-4386-9369-2f8f9028a6f7"}]->(b);
MATCH (a), (b) WHERE a.id = "15f481db-cb1e-439e-bb36-624f7b8954e3" AND b.id = "7199db51-14a6-4888-9ea8-cd4d6712463a"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "a445d041-6116-4915-a59d-a8953b51c168"}]->(b);
MATCH (a), (b) WHERE a.id = "b4375feb-f434-450b-be3e-efe3fb33fed4" AND b.id = "08eb8ea7-fe4d-4d67-8a39-a93c6b1e1005"
CREATE (a)-[:RELATED_TO {comment: "", label: "contains", id: "dd11417b-e3a9-4eb9-92ef-fef5ec5aa5b3"}]->(b);
MATCH (a), (b) WHERE a.id = "7199db51-14a6-4888-9ea8-cd4d6712463a" AND b.id = "18a0b15b-b64f-47c7-b422-5a9c990b4c2a"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "f5ff64cb-d51a-44fc-b1ce-b36406f27208"}]->(b);
MATCH (a), (b) WHERE a.id = "18a0b15b-b64f-47c7-b422-5a9c990b4c2a" AND b.id = "075b257d-6ec7-42a7-9dc0-704e0e6d9723"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "competes_with", id: "993de189-7b4d-4766-9e08-6e53dc46c38e"}]->(b);
MATCH (a), (b) WHERE a.id = "18a0b15b-b64f-47c7-b422-5a9c990b4c2a" AND b.id = "a1f4e795-feb6-4157-bb82-05b4bcef3f41"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "competes_with", id: "8f21f74d-d8ca-46c2-bf11-fbefa33261fe"}]->(b);
MATCH (a), (b) WHERE a.id = "1aea3d83-156c-4df9-82b2-d0504312871d" AND b.id = "08eb8ea7-fe4d-4d67-8a39-a93c6b1e1005"
CREATE (a)-[:RELATED_TO {comment: "", label: "employee_of", id: "cb090dfa-7e73-471f-bcab-5a6b51b7fb11"}]->(b);
MATCH (a), (b) WHERE a.id = "23183af4-f37e-4d54-a6b9-3d26793b1182" AND b.id = "18a0b15b-b64f-47c7-b422-5a9c990b4c2a"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "f7b14d06-9c84-47ae-b4fb-c4e50e9f2ec6"}]->(b);
MATCH (a), (b) WHERE a.id = "1aea3d83-156c-4df9-82b2-d0504312871d" AND b.id = "aaf4a42e-2b85-4b78-b216-18934453a10d"
CREATE (a)-[:RELATED_TO {comment: "", label: "citizen_of", id: "3003332a-2b67-4df2-8983-144235180d78"}]->(b);
MATCH (a), (b) WHERE a.id = "23183af4-f37e-4d54-a6b9-3d26793b1182" AND b.id = "15f481db-cb1e-439e-bb36-624f7b8954e3"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "1718f295-d15e-4b3c-ba6b-e18ecc2d78c8"}]->(b);
MATCH (a), (b) WHERE a.id = "1aea3d83-156c-4df9-82b2-d0504312871d" AND b.id = "b4375feb-f434-450b-be3e-efe3fb33fed4"
CREATE (a)-[:RELATED_TO {comment: "", label: "lives_in", id: "990234ea-79c3-413a-bd9b-1274c01b4d61"}]->(b);
MATCH (a), (b) WHERE a.id = "23183af4-f37e-4d54-a6b9-3d26793b1182" AND b.id = "7199db51-14a6-4888-9ea8-cd4d6712463a"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "29833b88-ef82-458f-bf6d-3ac55a89174f"}]->(b);
MATCH (a), (b) WHERE a.id = "23183af4-f37e-4d54-a6b9-3d26793b1182" AND b.id = "190673e3-f115-4455-ac30-59ce14b4098c"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "friend_of", id: "cb1b5588-8124-4152-ad18-752b8d5e482f"}]->(b);
MATCH (a), (b) WHERE a.id = "190673e3-f115-4455-ac30-59ce14b4098c" AND b.id = "18a0b15b-b64f-47c7-b422-5a9c990b4c2a"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "fa430013-023a-4dec-8909-7f210e9cc1bb"}]->(b);
MATCH (a), (b) WHERE a.id = "190673e3-f115-4455-ac30-59ce14b4098c" AND b.id = "15f481db-cb1e-439e-bb36-624f7b8954e3"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "10885c1d-2b90-4e8a-b2f1-eaf611bece8c"}]->(b);
MATCH (a), (b) WHERE a.id = "190673e3-f115-4455-ac30-59ce14b4098c" AND b.id = "9127f425-a6be-471a-b60d-85c041614d33"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "8806ff47-7e5f-43c2-b236-95f502c7f66d"}]->(b);
MATCH (a), (b) WHERE a.id = "190673e3-f115-4455-ac30-59ce14b4098c" AND b.id = "14069664-71bb-4896-9162-b3f50c6c21b9"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "friend_of", id: "19eb04c1-0479-4632-ac7d-4944ac1cae80"}]->(b);
MATCH (a), (b) WHERE a.id = "190673e3-f115-4455-ac30-59ce14b4098c" AND b.id = "ff34dcff-0db4-461c-b49a-1c2c2f040a86"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "friend_of", id: "d0121dad-0b00-4f77-ab24-7c5e94dda096"}]->(b);
MATCH (a), (b) WHERE a.id = "7199db51-14a6-4888-9ea8-cd4d6712463a" AND b.id = "075b257d-6ec7-42a7-9dc0-704e0e6d9723"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "2380c399-65af-4f75-9920-85eb09e47b73"}]->(b);
MATCH (a), (b) WHERE a.id = "9a58f942-77fa-45cb-b64f-8ef122f2b6ae" AND b.id = "075b257d-6ec7-42a7-9dc0-704e0e6d9723"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "79146812-ee25-4c65-b485-edf35aa04c47"}]->(b);
MATCH (a), (b) WHERE a.id = "9a58f942-77fa-45cb-b64f-8ef122f2b6ae" AND b.id = "15f481db-cb1e-439e-bb36-624f7b8954e3"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "81c2eca4-9dd5-4cca-aba7-bbe42130c810"}]->(b);
MATCH (a), (b) WHERE a.id = "9a58f942-77fa-45cb-b64f-8ef122f2b6ae" AND b.id = "7199db51-14a6-4888-9ea8-cd4d6712463a"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "274694d2-57f6-4dc3-94bd-0dfe8eaf1df8"}]->(b);
MATCH (a), (b) WHERE a.id = "c5b8e420-cf48-4b0c-8969-7858828b74c9" AND b.id = "075b257d-6ec7-42a7-9dc0-704e0e6d9723"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "1a6d0718-5e8e-40b8-a500-3d3c95d97ae7"}]->(b);
MATCH (a), (b) WHERE a.id = "c5b8e420-cf48-4b0c-8969-7858828b74c9" AND b.id = "ac652759-1ccf-4bc3-b608-fda0e55a72d6"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "8e85c281-859a-42c5-9f48-7a3e97eb5662"}]->(b);
MATCH (a), (b) WHERE a.id = "c5b8e420-cf48-4b0c-8969-7858828b74c9" AND b.id = "7410defc-0691-4cca-9fb6-3834942ff6e5"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "bdf9613a-66f0-4b80-ae2b-3ac03db88fbf"}]->(b);
MATCH (a), (b) WHERE a.id = "15f481db-cb1e-439e-bb36-624f7b8954e3" AND b.id = "9127f425-a6be-471a-b60d-85c041614d33"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "1239b000-112c-45d9-8ff5-aa8de6b1b91d"}]->(b);
MATCH (a), (b) WHERE a.id = "9127f425-a6be-471a-b60d-85c041614d33" AND b.id = "27b23549-1297-4a4c-8401-c09b8581685d"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "4d54e574-1251-498f-83c9-e69fbc49e623"}]->(b);
MATCH (a), (b) WHERE a.id = "c8d75213-2b15-481a-9a30-ebbdfcc2db0f" AND b.id = "27b23549-1297-4a4c-8401-c09b8581685d"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "9ec2a8ee-bf48-4eb6-b9c9-9dcc765a0550"}]->(b);
MATCH (a), (b) WHERE a.id = "c8d75213-2b15-481a-9a30-ebbdfcc2db0f" AND b.id = "15f481db-cb1e-439e-bb36-624f7b8954e3"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "545856ef-ecf7-4026-ad73-8bfc8527edb5"}]->(b);
MATCH (a), (b) WHERE a.id = "c8d75213-2b15-481a-9a30-ebbdfcc2db0f" AND b.id = "9127f425-a6be-471a-b60d-85c041614d33"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "e2d1d2e4-eb79-4036-a787-e2b34104ecbd"}]->(b);
MATCH (a), (b) WHERE a.id = "4bf1d6f5-b8a0-43d5-a553-c328ab00f2e5" AND b.id = "27b23549-1297-4a4c-8401-c09b8581685d"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "47e9c2f2-f64a-4659-8d67-4c2aa3a93ff2"}]->(b);
MATCH (a), (b) WHERE a.id = "4bf1d6f5-b8a0-43d5-a553-c328ab00f2e5" AND b.id = "15f481db-cb1e-439e-bb36-624f7b8954e3"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "17ec9a22-6e5e-431c-b39b-2441a30988ef"}]->(b);
MATCH (a), (b) WHERE a.id = "4bf1d6f5-b8a0-43d5-a553-c328ab00f2e5" AND b.id = "9127f425-a6be-471a-b60d-85c041614d33"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "0c361dd9-13ba-4088-b297-845ee5450bc7"}]->(b);
MATCH (a), (b) WHERE a.id = "9127f425-a6be-471a-b60d-85c041614d33" AND b.id = "277545f9-d8be-4c68-bb73-71f14b23d218"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "c0ff8828-fcb4-4533-b581-c1744b4a8cc8"}]->(b);
MATCH (a), (b) WHERE a.id = "397f2fcf-1589-42d9-9a67-3598b3264750" AND b.id = "277545f9-d8be-4c68-bb73-71f14b23d218"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "764b0663-abdb-4409-b78b-89efe8447ec8"}]->(b);
MATCH (a), (b) WHERE a.id = "397f2fcf-1589-42d9-9a67-3598b3264750" AND b.id = "15f481db-cb1e-439e-bb36-624f7b8954e3"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "4ad44a1e-a3cf-4326-8212-f3d671c59717"}]->(b);
MATCH (a), (b) WHERE a.id = "397f2fcf-1589-42d9-9a67-3598b3264750" AND b.id = "9127f425-a6be-471a-b60d-85c041614d33"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "0a426725-e04c-4723-bc97-805640cb9fea"}]->(b);
MATCH (a), (b) WHERE a.id = "397f2fcf-1589-42d9-9a67-3598b3264750" AND b.id = "2ecdf0e3-b661-4e3f-a697-7ef9bd8b56eb"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "friend_of", id: "4c6e7dde-1a2e-471a-afcf-f1519fe0e14d"}]->(b);
MATCH (a), (b) WHERE a.id = "bcc04d0e-e2c7-4a14-b33f-f6f23ce40ea6" AND b.id = "277545f9-d8be-4c68-bb73-71f14b23d218"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "142c9e6b-fb37-4f33-93a5-0a92a0b1e16f"}]->(b);
MATCH (a), (b) WHERE a.id = "bcc04d0e-e2c7-4a14-b33f-f6f23ce40ea6" AND b.id = "e728d9ae-b639-41dc-aaa4-dac8a6267e81"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "65f6eafd-2f1b-4474-ad65-c089690cc013"}]->(b);
MATCH (a), (b) WHERE a.id = "bcc04d0e-e2c7-4a14-b33f-f6f23ce40ea6" AND b.id = "65177d03-1f84-4b27-b7ae-289a88ec983e"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "72b17eca-437b-457c-8569-3a7754469022"}]->(b);
MATCH (a), (b) WHERE a.id = "bcc04d0e-e2c7-4a14-b33f-f6f23ce40ea6" AND b.id = "397f2fcf-1589-42d9-9a67-3598b3264750"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "friend_of", id: "7f41f29a-15e8-4837-8aac-ef51990eb8f9"}]->(b);
MATCH (a), (b) WHERE a.id = "ac652759-1ccf-4bc3-b608-fda0e55a72d6" AND b.id = "7410defc-0691-4cca-9fb6-3834942ff6e5"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "1fca9f57-4d9c-490b-b875-6fb1a92a71a0"}]->(b);
MATCH (a), (b) WHERE a.id = "7410defc-0691-4cca-9fb6-3834942ff6e5" AND b.id = "a1f4e795-feb6-4157-bb82-05b4bcef3f41"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "e59c4023-cfec-45f1-8557-832fe99794f5"}]->(b);
MATCH (a), (b) WHERE a.id = "a1f4e795-feb6-4157-bb82-05b4bcef3f41" AND b.id = "bf3402c5-4a5b-44b6-9ad2-c8905a271755"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "competes_with", id: "696db88e-1c0f-4b10-b5f2-be3494c0c501"}]->(b);
MATCH (a), (b) WHERE a.id = "14069664-71bb-4896-9162-b3f50c6c21b9" AND b.id = "a1f4e795-feb6-4157-bb82-05b4bcef3f41"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "cea13b16-e6e6-4caf-8608-a5b04045d010"}]->(b);
MATCH (a), (b) WHERE a.id = "14069664-71bb-4896-9162-b3f50c6c21b9" AND b.id = "ac652759-1ccf-4bc3-b608-fda0e55a72d6"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "0aad82a9-91ae-467d-8f29-c89cf17fc996"}]->(b);
MATCH (a), (b) WHERE a.id = "14069664-71bb-4896-9162-b3f50c6c21b9" AND b.id = "7410defc-0691-4cca-9fb6-3834942ff6e5"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "bc12637d-4524-4cbe-b147-a1de526b2db9"}]->(b);
MATCH (a), (b) WHERE a.id = "434cf4ed-f3f0-428f-8b4c-cb2b9f614f86" AND b.id = "a1f4e795-feb6-4157-bb82-05b4bcef3f41"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "547882f4-e2f9-4591-9bde-b13fffb91030"}]->(b);
MATCH (a), (b) WHERE a.id = "434cf4ed-f3f0-428f-8b4c-cb2b9f614f86" AND b.id = "ac652759-1ccf-4bc3-b608-fda0e55a72d6"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "7bbee699-7e4d-4960-a3e7-356f9cb111c7"}]->(b);
MATCH (a), (b) WHERE a.id = "434cf4ed-f3f0-428f-8b4c-cb2b9f614f86" AND b.id = "7410defc-0691-4cca-9fb6-3834942ff6e5"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "1232a69a-87da-46ff-a33f-334ed2d31561"}]->(b);
MATCH (a), (b) WHERE a.id = "7410defc-0691-4cca-9fb6-3834942ff6e5" AND b.id = "bf3402c5-4a5b-44b6-9ad2-c8905a271755"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "80ea223e-9059-4d8b-9eec-79c7b58ac892"}]->(b);
MATCH (a), (b) WHERE a.id = "bf3402c5-4a5b-44b6-9ad2-c8905a271755" AND b.id = "8cc4e333-89f6-4099-b0a5-e55ba26fc394"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "competes_with", id: "042d7486-ea9e-429d-97c7-b9168aa691cf"}]->(b);
MATCH (a), (b) WHERE a.id = "3f6f5d51-115e-4424-9e10-c5fc8fd46b2e" AND b.id = "bf3402c5-4a5b-44b6-9ad2-c8905a271755"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "9a0f97d8-5136-46e8-9b6e-aa30f4abc3d2"}]->(b);
MATCH (a), (b) WHERE a.id = "3f6f5d51-115e-4424-9e10-c5fc8fd46b2e" AND b.id = "ac652759-1ccf-4bc3-b608-fda0e55a72d6"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "0d289fdb-3c96-4cbd-b1d3-90ec7bab2d35"}]->(b);
MATCH (a), (b) WHERE a.id = "3f6f5d51-115e-4424-9e10-c5fc8fd46b2e" AND b.id = "7410defc-0691-4cca-9fb6-3834942ff6e5"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "ec3b5f61-51d8-4448-8cd8-9ccb18b3a1b2"}]->(b);
MATCH (a), (b) WHERE a.id = "433a1421-4396-4d74-988e-6379100d884b" AND b.id = "bf3402c5-4a5b-44b6-9ad2-c8905a271755"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "fe8c31d0-6c2b-4e9c-89f0-6d2872c36a6d"}]->(b);
MATCH (a), (b) WHERE a.id = "433a1421-4396-4d74-988e-6379100d884b" AND b.id = "ac652759-1ccf-4bc3-b608-fda0e55a72d6"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "4d2ed21e-9e04-429f-8116-320a5bc81012"}]->(b);
MATCH (a), (b) WHERE a.id = "433a1421-4396-4d74-988e-6379100d884b" AND b.id = "7410defc-0691-4cca-9fb6-3834942ff6e5"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "d1053e84-e332-46e9-af2b-2b8ae3b476f0"}]->(b);
MATCH (a), (b) WHERE a.id = "ac652759-1ccf-4bc3-b608-fda0e55a72d6" AND b.id = "e68e3286-bf37-49bf-aaa1-9bf91592d7c6"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "b7332bea-d8b0-409e-8ec1-7aadc6738c84"}]->(b);
MATCH (a), (b) WHERE a.id = "e68e3286-bf37-49bf-aaa1-9bf91592d7c6" AND b.id = "b6367ae3-3a83-402d-9f47-e1a51ba2522d"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "838a908c-ece0-43bb-8ae6-a45ff1012902"}]->(b);
MATCH (a), (b) WHERE a.id = "ff34dcff-0db4-461c-b49a-1c2c2f040a86" AND b.id = "b6367ae3-3a83-402d-9f47-e1a51ba2522d"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "32ec811d-adce-4ea6-920b-7cfb624fc304"}]->(b);
MATCH (a), (b) WHERE a.id = "ff34dcff-0db4-461c-b49a-1c2c2f040a86" AND b.id = "ac652759-1ccf-4bc3-b608-fda0e55a72d6"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "0469658a-f9e6-4e5d-9000-798d01926208"}]->(b);
MATCH (a), (b) WHERE a.id = "ff34dcff-0db4-461c-b49a-1c2c2f040a86" AND b.id = "e68e3286-bf37-49bf-aaa1-9bf91592d7c6"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "6e01af70-3725-410f-a681-89d29a135ced"}]->(b);
MATCH (a), (b) WHERE a.id = "ff9db98a-db77-4c74-a363-84a68bbb5a4c" AND b.id = "b6367ae3-3a83-402d-9f47-e1a51ba2522d"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "d1a39ef1-dd01-4738-bdc7-985adc1354c4"}]->(b);
MATCH (a), (b) WHERE a.id = "ff9db98a-db77-4c74-a363-84a68bbb5a4c" AND b.id = "ac652759-1ccf-4bc3-b608-fda0e55a72d6"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "4cba7abe-de35-47f5-b648-0b79a3260e7b"}]->(b);
MATCH (a), (b) WHERE a.id = "ff9db98a-db77-4c74-a363-84a68bbb5a4c" AND b.id = "e68e3286-bf37-49bf-aaa1-9bf91592d7c6"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "16d3ec8a-fcfe-4644-ae94-f71dffef0349"}]->(b);
MATCH (a), (b) WHERE a.id = "e68e3286-bf37-49bf-aaa1-9bf91592d7c6" AND b.id = "57043c13-a9ea-4c4b-be7b-ad86287ec8f7"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "17d9d641-d6ef-4347-bc95-e1a4fbd391cd"}]->(b);
MATCH (a), (b) WHERE a.id = "9639d13a-b650-497d-80dd-1c3058339f75" AND b.id = "57043c13-a9ea-4c4b-be7b-ad86287ec8f7"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "5e2a8201-c924-45ff-ba4e-a0961ce3e81d"}]->(b);
MATCH (a), (b) WHERE a.id = "9639d13a-b650-497d-80dd-1c3058339f75" AND b.id = "ac652759-1ccf-4bc3-b608-fda0e55a72d6"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "a860353b-526f-4035-a565-389a64e457d6"}]->(b);
MATCH (a), (b) WHERE a.id = "9639d13a-b650-497d-80dd-1c3058339f75" AND b.id = "e68e3286-bf37-49bf-aaa1-9bf91592d7c6"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "e979da44-6555-4da8-9eb2-a3dd55e0ac50"}]->(b);
MATCH (a), (b) WHERE a.id = "72d29de1-8a42-425f-a193-31557513e4d9" AND b.id = "57043c13-a9ea-4c4b-be7b-ad86287ec8f7"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "42c3b2fd-0f46-42f1-8a04-06cce49f777d"}]->(b);
MATCH (a), (b) WHERE a.id = "72d29de1-8a42-425f-a193-31557513e4d9" AND b.id = "ac652759-1ccf-4bc3-b608-fda0e55a72d6"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "b82f7f9a-bb8f-419e-a831-96fbdb198495"}]->(b);
MATCH (a), (b) WHERE a.id = "72d29de1-8a42-425f-a193-31557513e4d9" AND b.id = "e68e3286-bf37-49bf-aaa1-9bf91592d7c6"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "aa479a40-53bf-4d59-9cb6-c965fab4ab0a"}]->(b);
MATCH (a), (b) WHERE a.id = "e728d9ae-b639-41dc-aaa4-dac8a6267e81" AND b.id = "65177d03-1f84-4b27-b7ae-289a88ec983e"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "105d2193-279d-4494-ba79-bf02277a9d8c"}]->(b);
MATCH (a), (b) WHERE a.id = "65177d03-1f84-4b27-b7ae-289a88ec983e" AND b.id = "1e559a30-f561-4ba8-90b2-c1a81a4fda8c"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "8fed82df-c18c-45eb-9e0b-4663357511e6"}]->(b);
MATCH (a), (b) WHERE a.id = "2ecdf0e3-b661-4e3f-a697-7ef9bd8b56eb" AND b.id = "1e559a30-f561-4ba8-90b2-c1a81a4fda8c"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "9e994d31-f729-4cd6-aa89-05e0067744fe"}]->(b);
MATCH (a), (b) WHERE a.id = "2ecdf0e3-b661-4e3f-a697-7ef9bd8b56eb" AND b.id = "e728d9ae-b639-41dc-aaa4-dac8a6267e81"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "0d7c968c-c657-45c1-abda-d6348d999f7f"}]->(b);
MATCH (a), (b) WHERE a.id = "2ecdf0e3-b661-4e3f-a697-7ef9bd8b56eb" AND b.id = "65177d03-1f84-4b27-b7ae-289a88ec983e"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "ce28f818-70b1-49be-a166-23b33fdcf698"}]->(b);
MATCH (a), (b) WHERE a.id = "2ecdf0e3-b661-4e3f-a697-7ef9bd8b56eb" AND b.id = "bcc04d0e-e2c7-4a14-b33f-f6f23ce40ea6"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "friend_of", id: "f6a40266-1e9f-4872-be54-e85976723588"}]->(b);
MATCH (a), (b) WHERE a.id = "1c59efb0-9029-433f-8b15-96da7e831e8d" AND b.id = "1e559a30-f561-4ba8-90b2-c1a81a4fda8c"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "c309fa94-9fce-42b3-affc-203360e899ad"}]->(b);
MATCH (a), (b) WHERE a.id = "1c59efb0-9029-433f-8b15-96da7e831e8d" AND b.id = "e728d9ae-b639-41dc-aaa4-dac8a6267e81"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "347477f4-ad5c-42ea-b13e-29470d03303a"}]->(b);
MATCH (a), (b) WHERE a.id = "1c59efb0-9029-433f-8b15-96da7e831e8d" AND b.id = "65177d03-1f84-4b27-b7ae-289a88ec983e"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "7496ebac-a93b-4260-b8d6-524208236ec5"}]->(b);
MATCH (a), (b) WHERE a.id = "65177d03-1f84-4b27-b7ae-289a88ec983e" AND b.id = "6fd2af8e-ccf1-4ec0-a1eb-c10c17e8e32a"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "c33b86db-3c92-46ec-a5ca-27bbbf6fb05c"}]->(b);
MATCH (a), (b) WHERE a.id = "7c029791-ab67-405b-8ccf-a2a4c0534efd" AND b.id = "6fd2af8e-ccf1-4ec0-a1eb-c10c17e8e32a"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "8e23fb5e-e90e-49bc-9026-66779a3c9dc4"}]->(b);
MATCH (a), (b) WHERE a.id = "7c029791-ab67-405b-8ccf-a2a4c0534efd" AND b.id = "e728d9ae-b639-41dc-aaa4-dac8a6267e81"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "f9867a0d-d4e4-4644-829c-ee63cb1162a9"}]->(b);
MATCH (a), (b) WHERE a.id = "7c029791-ab67-405b-8ccf-a2a4c0534efd" AND b.id = "65177d03-1f84-4b27-b7ae-289a88ec983e"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "8ed5c4d0-bd97-4427-bd55-ec7365d9cb2e"}]->(b);
MATCH (a), (b) WHERE a.id = "8c93c2f1-82c3-4d38-8d09-f4ad3e4e3228" AND b.id = "6fd2af8e-ccf1-4ec0-a1eb-c10c17e8e32a"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "0aa5bf4d-9286-4616-b8f0-563cad76faed"}]->(b);
MATCH (a), (b) WHERE a.id = "8c93c2f1-82c3-4d38-8d09-f4ad3e4e3228" AND b.id = "e728d9ae-b639-41dc-aaa4-dac8a6267e81"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "422dd37e-8a23-4508-8151-b5390c49593b"}]->(b);
MATCH (a), (b) WHERE a.id = "8c93c2f1-82c3-4d38-8d09-f4ad3e4e3228" AND b.id = "65177d03-1f84-4b27-b7ae-289a88ec983e"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "77a249c4-00d3-4c28-aa64-72916ecec445"}]->(b);
MATCH (a), (b) WHERE a.id = "e728d9ae-b639-41dc-aaa4-dac8a6267e81" AND b.id = "6b025658-69c0-45d1-adba-1aba354600e5"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "569f40d8-f212-45a4-b493-4d94ac271d59"}]->(b);
MATCH (a), (b) WHERE a.id = "6b025658-69c0-45d1-adba-1aba354600e5" AND b.id = "8cc4e333-89f6-4099-b0a5-e55ba26fc394"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "66aa29f7-6ec9-40d4-b249-6b8cb128319a"}]->(b);
MATCH (a), (b) WHERE a.id = "a6407575-9260-4045-9dad-ebfa2f74a47f" AND b.id = "8cc4e333-89f6-4099-b0a5-e55ba26fc394"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "976f6ee3-9dcf-4eb4-a41e-9091c19cf820"}]->(b);
MATCH (a), (b) WHERE a.id = "a6407575-9260-4045-9dad-ebfa2f74a47f" AND b.id = "e728d9ae-b639-41dc-aaa4-dac8a6267e81"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "5bb4f6eb-0880-4a37-94fb-7d082940a519"}]->(b);
MATCH (a), (b) WHERE a.id = "a6407575-9260-4045-9dad-ebfa2f74a47f" AND b.id = "7199db51-14a6-4888-9ea8-cd4d6712463a"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "cd1ec19b-e517-4129-bd94-31ad7a83325d"}]->(b);
MATCH (a), (b) WHERE a.id = "15da7a00-3068-48ea-ad1a-da7a22e31ef4" AND b.id = "8cc4e333-89f6-4099-b0a5-e55ba26fc394"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "022df71a-0cbc-4400-a323-ce263078e021"}]->(b);
MATCH (a), (b) WHERE a.id = "15da7a00-3068-48ea-ad1a-da7a22e31ef4" AND b.id = "e728d9ae-b639-41dc-aaa4-dac8a6267e81"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "79d13244-10dd-4be6-9319-d888a51acfcc"}]->(b);
MATCH (a), (b) WHERE a.id = "15da7a00-3068-48ea-ad1a-da7a22e31ef4" AND b.id = "6b025658-69c0-45d1-adba-1aba354600e5"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "6658243e-67dd-4afb-a930-18985f91baf6"}]->(b);
MATCH (a), (b) WHERE a.id = "6b025658-69c0-45d1-adba-1aba354600e5" AND b.id = "1ad4e3d1-d5d8-44d6-b517-d3516f6f615b"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "2447618d-ef87-4b54-a1e7-ae529dc1876d"}]->(b);
MATCH (a), (b) WHERE a.id = "69311012-c6ba-416f-8ea2-ba14f881d8ed" AND b.id = "1ad4e3d1-d5d8-44d6-b517-d3516f6f615b"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "f8c2c636-6e24-4fef-87e0-044a8121bc6c"}]->(b);
MATCH (a), (b) WHERE a.id = "69311012-c6ba-416f-8ea2-ba14f881d8ed" AND b.id = "e728d9ae-b639-41dc-aaa4-dac8a6267e81"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "2d0a8611-65de-4b74-8c54-a006271b67de"}]->(b);
MATCH (a), (b) WHERE a.id = "69311012-c6ba-416f-8ea2-ba14f881d8ed" AND b.id = "6b025658-69c0-45d1-adba-1aba354600e5"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "bb008692-6db7-49f2-8f91-9ce21b09e1ed"}]->(b);
MATCH (a), (b) WHERE a.id = "b23d313f-1554-4fb5-9ba0-b11d39465644" AND b.id = "1ad4e3d1-d5d8-44d6-b517-d3516f6f615b"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "8b26d773-8809-4ffe-a921-199791f57c5e"}]->(b);
MATCH (a), (b) WHERE a.id = "b23d313f-1554-4fb5-9ba0-b11d39465644" AND b.id = "e728d9ae-b639-41dc-aaa4-dac8a6267e81"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "5701042d-ee32-48e7-bcaf-3ddf2ee17bcf"}]->(b);
MATCH (a), (b) WHERE a.id = "b23d313f-1554-4fb5-9ba0-b11d39465644" AND b.id = "6b025658-69c0-45d1-adba-1aba354600e5"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "304d853d-86bc-44aa-95a8-733c2e3b31fe"}]->(b);
