MATCH (n) WITH n DETACH DELETE n;
CREATE (n:Namespace {depositVersion: "2.0.0-dev.51-dirty", name: "TestNamespace", description: "", id: "7eb35647-7b56-4121-890c-7d239c293cb0", versioningType: ""});
CREATE (n:Object {position: [], id: "4ca16ea8-1996-4302-b2ad-e0ef91f63c86"});
CREATE (n:Class {name: "Country", comment: "", id: "5f3779d8-801c-4161-af3a-4a17358c7c59", position: [], order: 1});
CREATE (n:Descriptor {name: "Name", comment: "", id: "e1e75fdd-21dd-490e-b4c8-cc26753df660", type: "String", order: -1});
CREATE (n:Descriptor {name: "Name", id: "b1111978-9dd5-469f-856b-dfcadaefc8b9", value: "Austria"});
CREATE (n:Object {position: [], id: "0fc4c135-bfa2-4ac0-bd23-d4acaeedca53"});
CREATE (n:Class {name: "City", comment: "", position: [], id: "831f2639-459b-4471-b0fa-f98866a23bfd", order: 2});
CREATE (n:Descriptor {name: "Name", comment: "", id: "8f423e37-5975-40e2-9b71-c272366f30a4", type: "String", order: -1});
CREATE (n:Descriptor {name: "Name", id: "b8fdfee4-ef23-48e4-8c73-560a12f8d24a", value: "Vienna"});
CREATE (n:Object {position: [], id: "b274e751-7cc3-480b-8aa2-59134ce46862"});
CREATE (n:Class {name: "Company", comment: "", id: "45efcce7-a92b-4aea-a785-e3c598b44f4a", position: [], order: 3});
CREATE (n:Descriptor {name: "Name", comment: "", id: "742dd5b0-085f-44f4-ace2-7b89638747ff", type: "String", order: -1});
CREATE (n:Descriptor {name: "Name", id: "429dee63-f4cc-4f9d-94de-06b0b9ca545f", value: "Velocipeds"});
CREATE (n:Object {position: [], id: "94d735fe-db72-4861-9ea6-5a51962e5936"});
CREATE (n:Descriptor {name: "Name", id: "24348bbc-d0eb-4526-b78c-12605237414b", value: "Voleyballs"});
CREATE (n:Object {position: [], id: "ddafb0c6-9ba1-47ce-95de-415839048d98"});
CREATE (n:Descriptor {name: "Name", id: "fff78beb-4851-4c94-a971-d3e0da1544d4", value: "Gamma"});
CREATE (n:Object {position: [], id: "49d0c84e-9ae9-494d-95b3-d725a6335fcc"});
CREATE (n:Class {name: "Person", comment: "", id: "c2a61508-1c01-403a-8daa-6bcbf667c0d8", position: [], order: 4});
CREATE (n:Descriptor {name: "Name", comment: "", id: "0c6764cf-3d68-41bb-802c-4585af2d8ac4", type: "String", order: -1});
CREATE (n:Descriptor {name: "Name", id: "88a427e0-f2ab-4f75-afff-555d4407e471", value: "Adam"});
CREATE (n:DResource {storedFilename: "Adam-feaf123b-8aee-4250-8743-3178e83f9e36.tmp", imageFormat: "", filename: "Adam.tmp", id: "f369a64e-7f24-4263-9f2c-decc760bc003"});
CREATE (n:Descriptor {name: "File", comment: "", id: "97246317-c062-4072-a71d-a5b559dd78c5", type: "DResource", order: -1});
CREATE (n:Descriptor {name: "File", id: "72feaffb-fcee-4ca9-b3bf-c4eb2390ec75", value: "Adam.tmp"});
CREATE (n:Object {position: [], id: "92868009-deeb-4eb8-88fa-6197254faef4"});
CREATE (n:Descriptor {name: "Name", id: "bba75caa-beba-4bac-9123-72e3b7af0b95", value: "Alex"});
CREATE (n:DResource {storedFilename: "Alex-2a529575-7ae2-470a-8187-3248c1484c10.tmp", imageFormat: "", filename: "Alex.tmp", id: "0f8ff612-d8c8-443b-ab30-1abf041411cd"});
CREATE (n:Descriptor {name: "File", id: "369434eb-3659-4f44-a2c0-0eca06c0abb9", value: "Alex.tmp"});
CREATE (n:Object {position: [], id: "8d2441bd-8e61-4358-a40a-18e7dab88527"});
CREATE (n:Descriptor {name: "Name", id: "de47987a-1247-4b57-9183-7d91a37f39e6", value: "Linz"});
CREATE (n:Object {position: [], id: "94be0006-7f27-4dc0-a405-f021aea002d1"});
CREATE (n:Descriptor {name: "Name", id: "faa31711-3dea-4a5a-9f52-d07772d1becb", value: "Hugo"});
CREATE (n:DResource {storedFilename: "Hugo-35cbd53f-24af-4b89-a04e-8fa8ca521150.tmp", imageFormat: "", filename: "Hugo.tmp", id: "4f23391f-f6a7-4760-84c8-c15b52bf57bc"});
CREATE (n:Descriptor {name: "File", id: "d7a2774b-52d2-4177-8009-051cc521a5b3", value: "Hugo.tmp"});
CREATE (n:Object {position: [], id: "1f50b3bd-283a-4216-866d-46d5e74d2933"});
CREATE (n:Descriptor {name: "Name", id: "d976fbd5-5002-4f19-be47-987124d1db81", value: "Lisa"});
CREATE (n:DResource {storedFilename: "Lisa-ec9e462b-8cc1-4f98-a2c3-a4205bef2a75.tmp", imageFormat: "", filename: "Lisa.tmp", id: "b95aaf42-c319-40aa-aa0a-d7cf286a3ed0"});
CREATE (n:Descriptor {name: "File", id: "a0e792fe-d225-4841-8173-8806c93759ed", value: "Lisa.tmp"});
CREATE (n:Object {position: [], id: "afdfff74-f0d2-4d2f-b4ca-3e4fb7a6a58f"});
CREATE (n:Descriptor {name: "Name", id: "c3bb39a0-481a-432d-850d-019f46cc3c68", value: "Anna"});
CREATE (n:DResource {storedFilename: "Anna-6bc88f92-6068-404f-bb09-c2d39a997abc.tmp", imageFormat: "", filename: "Anna.tmp", id: "c0b6c7e4-a368-42cd-9cbb-608cc39344a7"});
CREATE (n:Descriptor {name: "File", id: "9b5dac52-78ab-46b4-addd-4fc6432673cd", value: "Anna.tmp"});
CREATE (n:Object {position: [], id: "fd1cbafd-ddc9-47c0-81cd-6b5836f67ca4"});
CREATE (n:Descriptor {name: "Name", id: "fb634b5f-c884-483b-95d6-7df54f1b235f", value: "Ben"});
CREATE (n:DResource {storedFilename: "Ben-5718bf05-9e65-45ff-86ba-d8f67c482adc.tmp", imageFormat: "", filename: "Ben.tmp", id: "e5ffc020-1899-4f2c-9769-823ea513308e"});
CREATE (n:Descriptor {name: "File", id: "629e0bcf-e053-40ff-91d4-ef79dd7f95ee", value: "Ben.tmp"});
CREATE (n:Object {position: [], id: "d94060b2-b8ec-4fd1-a232-a8f5e4750fed"});
CREATE (n:Descriptor {name: "Name", id: "3ef26525-f63a-47d9-9b96-739f4398e97f", value: "Belgium"});
CREATE (n:Object {position: [], id: "01d34d74-2f74-4ba5-97b6-de52e54db6cf"});
CREATE (n:Descriptor {name: "Name", id: "10c69f3c-3b0c-4594-b59d-61741463ec4b", value: "Ghent"});
CREATE (n:Object {position: [], id: "23ba876b-35cc-4fc1-a433-b4497b800882"});
CREATE (n:Descriptor {name: "Name", id: "b85f0a14-1c77-4f6f-878e-90de758a4a8a", value: "Logs"});
CREATE (n:Object {position: [], id: "ed1b56e3-86ac-4e88-b28b-9ef7c1b97c5e"});
CREATE (n:Descriptor {name: "Name", id: "eae6effb-6fdc-486b-842f-20f498076a7d", value: "Clara"});
CREATE (n:DResource {storedFilename: "Clara-1bac8381-e9d8-4738-8ba9-64ba227cee8e.tmp", imageFormat: "", filename: "Clara.tmp", id: "a7892a4c-87ab-49e8-b8cb-9a924d5c17db"});
CREATE (n:Descriptor {name: "File", id: "f6fc5ee4-2ca0-4736-bac1-167841956cff", value: "Clara.tmp"});
CREATE (n:Object {position: [], id: "b129b455-38c4-4521-929e-3ff5784471f6"});
CREATE (n:Descriptor {name: "Name", id: "3038f1b1-5f37-46fe-bd86-a67ca91be330", value: "Ella"});
CREATE (n:DResource {storedFilename: "Ella-0d3fdc63-8524-405b-bd5c-d082824885a0.tmp", imageFormat: "", filename: "Ella.tmp", id: "767981cf-77a2-47e9-b758-a0dba9ae3a18"});
CREATE (n:Descriptor {name: "File", id: "55c5f2a9-a81c-4493-95d6-def118a69b3e", value: "Ella.tmp"});
CREATE (n:Object {position: [], id: "d2b0298d-6afc-4412-883b-a191973b8241"});
CREATE (n:Descriptor {name: "Name", id: "ca4b500a-5f6a-4279-86ba-630e31e87006", value: "Lentils"});
CREATE (n:Object {position: [], id: "a06f771d-de1a-4ffa-8cfb-93527e85ce75"});
CREATE (n:Descriptor {name: "Name", id: "7c405c21-7b13-4ef4-8901-73a198eb82b5", value: "Emma"});
CREATE (n:DResource {storedFilename: "Emma-9f8ca5db-496d-4e5f-8f58-73809cf664c0.tmp", imageFormat: "", filename: "Emma.tmp", id: "a56fda6c-df6d-480b-b3e2-3ce0b11cd264"});
CREATE (n:Descriptor {name: "File", id: "0436e34e-18e5-4e0f-ad8b-eb8119d66565", value: "Emma.tmp"});
CREATE (n:Object {position: [], id: "8d5de1b7-e6ab-43f9-8fc1-deaab75ad033"});
CREATE (n:Descriptor {name: "Name", id: "4c1c57b7-069a-49f3-bc48-f46905e68dc9", value: "Mia"});
CREATE (n:DResource {storedFilename: "Mia-33c4bca8-70c2-4d7d-81b1-315a87d3212b.tmp", imageFormat: "", filename: "Mia.tmp", id: "b824be24-e5d4-4df4-8a07-f5c7871b4c8b"});
CREATE (n:Descriptor {name: "File", id: "3ef2431f-cda1-46fd-b264-a8002f82d1b7", value: "Mia.tmp"});
CREATE (n:Object {position: [], id: "05fe86e5-25df-44fb-8c29-c02d4785617a"});
CREATE (n:Descriptor {name: "Name", id: "aa25b2c3-e6db-4006-837e-8b8dbfd24831", value: "Eva"});
CREATE (n:DResource {storedFilename: "Eva-7feca1c4-55e4-4b1e-908d-15a71ce28762.tmp", imageFormat: "", filename: "Eva.tmp", id: "2a1e5939-8aba-4a12-a11e-9a88a6eacf1c"});
CREATE (n:Descriptor {name: "File", id: "312b735f-f0d6-4b13-a897-9cf4f2c6ea9f", value: "Eva.tmp"});
CREATE (n:Object {position: [], id: "30dccb5e-779b-4ed1-89b7-b2104c4f0cfb"});
CREATE (n:Descriptor {name: "Name", id: "69743eda-42ac-49bd-bc31-785be6f1602d", value: "Czechoslovakia"});
CREATE (n:Object {position: [], id: "ee015145-df29-482b-9714-8da29c995e3f"});
CREATE (n:Descriptor {name: "Name", id: "9e25a694-9588-48bb-b0ef-929aa67f412e", value: "Prague"});
CREATE (n:Object {position: [], id: "0f578787-4c5b-4c78-8bda-609ba3059940"});
CREATE (n:Descriptor {name: "Name", id: "766e577c-c1e8-45b8-9d0e-95f5db559423", value: "Gothic"});
CREATE (n:Object {position: [], id: "d860ddd7-8029-4567-8695-e54fb4d0537a"});
CREATE (n:Descriptor {name: "Name", id: "d6b68077-b5bd-404e-ad57-efea76adb062", value: "Ian"});
CREATE (n:DResource {storedFilename: "Ian-7f25b52b-3b2e-47a5-9477-25ee9c6409ad.tmp", imageFormat: "", filename: "Ian.tmp", id: "915c77e6-4ab3-441d-8fce-7ec53fe00555"});
CREATE (n:Descriptor {name: "File", id: "64e451b4-3369-4d31-9ec3-2619fe75d018", value: "Ian.tmp"});
CREATE (n:Object {position: [], id: "9952a9f9-b4e8-4c57-bb9d-e94cad4ab14d"});
CREATE (n:Descriptor {name: "Name", id: "d0be139a-359e-4014-99c9-50f0516d4e60", value: "Beta"});
CREATE (n:Object {position: [], id: "26660adc-5464-4c1f-8552-4a14be154220"});
CREATE (n:Descriptor {name: "Name", id: "cd9eb96b-d2c7-4239-8b4d-0927654bb378", value: "Julia"});
CREATE (n:DResource {storedFilename: "Julia-f5e46835-8a61-4c13-be5e-fd1c26c7b58c.tmp", imageFormat: "", filename: "Julia.tmp", id: "b92d9d96-8a2c-4128-a090-8378cf0178a1"});
CREATE (n:Descriptor {name: "File", id: "6710cede-8d35-4f38-9956-20b773c5355e", value: "Julia.tmp"});
CREATE (n:Object {position: [], id: "50c1a29b-ede7-4dee-8bab-99556949ed97"});
CREATE (n:Descriptor {name: "Name", id: "136e92c4-ef73-4d93-a338-1be7f911d8d6", value: "Leo"});
CREATE (n:DResource {storedFilename: "Leo-a1c10099-898a-444c-85fa-49633274bb3a.tmp", imageFormat: "", filename: "Leo.tmp", id: "eec9cb82-7c9b-4e66-a2aa-601d52e44b24"});
CREATE (n:Descriptor {name: "File", id: "4fe3ed5c-0241-4e49-bf78-c90d18bf7c2c", value: "Leo.tmp"});
CREATE (n:Object {position: [], id: "7b9d3af7-f336-4464-8c22-e3a4899f3fb2"});
CREATE (n:Descriptor {name: "Name", id: "9e450c6f-209f-4fe9-b2bc-5275ccaa629d", value: "Antwerp"});
CREATE (n:Object {position: [], id: "40bbd1fb-d85d-4c69-b17a-f908938cd652"});
CREATE (n:Descriptor {name: "Name", id: "01bf9c5f-47a9-4ccd-a532-3225ca11c2dc", value: "Alpha"});
CREATE (n:Object {position: [], id: "63025fdb-e9d1-4b66-9d59-f959cefeb788"});
CREATE (n:Descriptor {name: "Name", id: "e681042d-fe27-41f0-8f83-cf3526962a45", value: "Luca"});
CREATE (n:DResource {storedFilename: "Luca-3a6ad30d-a0bb-4e32-a307-f2ab7d81e825.tmp", imageFormat: "", filename: "Luca.tmp", id: "4666776c-8c82-489f-b18f-e914ed6d93bb"});
CREATE (n:Descriptor {name: "File", id: "71b240b6-9287-463f-a009-4d3351553484", value: "Luca.tmp"});
CREATE (n:Object {position: [], id: "7ce79b68-4f24-48e5-8af6-cc0f00e1760e"});
CREATE (n:Descriptor {name: "Name", id: "d337a7e3-2135-40ac-b9f4-8ee1c1dca81f", value: "Automobiles"});
CREATE (n:Object {position: [], id: "d50f4b84-82a6-4b64-b335-20d4558c034a"});
CREATE (n:Descriptor {name: "Name", id: "a80684ce-18f5-423b-88db-ee11ce6f987e", value: "Mark"});
CREATE (n:DResource {storedFilename: "Mark-73008a08-e8b3-4c3f-bc0a-481290de74fa.tmp", imageFormat: "", filename: "Mark.tmp", id: "d7f8f43b-7f3c-48ac-9b6c-bedcc57bc41e"});
CREATE (n:Descriptor {name: "File", id: "a9e7abba-3b75-447b-be44-8c7c9aba2b33", value: "Mark.tmp"});
CREATE (n:Object {position: [], id: "a8cb68eb-c5d7-47db-bc80-80671a3e8ba1"});
CREATE (n:Descriptor {name: "Name", id: "60e15e27-ca52-4e2e-a1bc-66ef0f4f25d6", value: "Max"});
CREATE (n:DResource {storedFilename: "Max-43a3fe5c-5add-4c35-8b8d-70fd96ecfe77.tmp", imageFormat: "", filename: "Max.tmp", id: "7ecd5f03-b221-4e47-8af0-47932b50ac3b"});
CREATE (n:Descriptor {name: "File", id: "a8397fb3-b058-4bc7-abcc-afa074f3830f", value: "Max.tmp"});
CREATE (n:Object {position: [], id: "4084a781-bf45-4dfa-b406-4a8a82122f33"});
CREATE (n:Descriptor {name: "Name", id: "987c22c7-0a12-4a1c-b4e5-b2dec3ea48c4", value: "Pilsner"});
CREATE (n:Object {position: [], id: "dce8b130-a524-4816-af8b-1434f95a9339"});
CREATE (n:Descriptor {name: "Name", id: "fe8c15cd-efed-438a-8a6f-adc6fbcff297", value: "Nora"});
CREATE (n:DResource {storedFilename: "Nora-f764cd6b-634a-49f7-8b3f-1ada80890013.tmp", imageFormat: "", filename: "Nora.tmp", id: "73651c48-3fb9-49dc-bde4-7affef806ad5"});
CREATE (n:Descriptor {name: "File", id: "49d67b74-3d96-4597-a366-26e9d6f0d9a2", value: "Nora.tmp"});
CREATE (n:Object {position: [], id: "dccf82c6-4bd4-44ae-9ab1-84cd33773d94"});
CREATE (n:Descriptor {name: "Name", id: "1cf8e1ca-e4c7-48f6-a5db-dd422c449d2b", value: "Papermill"});
CREATE (n:Object {position: [], id: "baf8c6ef-602f-4da6-bd18-3064c707c70f"});
CREATE (n:Descriptor {name: "Name", id: "38cb24bf-2731-491d-abe1-b3cc018e739a", value: "Paul"});
CREATE (n:DResource {storedFilename: "Paul-9d6b42e9-cd6a-4a6b-9e5c-8dfc3e1bd0fb.tmp", imageFormat: "", filename: "Paul.tmp", id: "c042d3a0-aa59-43fb-88ec-b41ff6526dd8"});
CREATE (n:Descriptor {name: "File", id: "19ec7d87-5b80-4e54-a3b4-6fc234b7ae53", value: "Paul.tmp"});
CREATE (n:Object {position: [], id: "e86332b6-d02f-44b2-93f7-e8e420880c51"});
CREATE (n:Descriptor {name: "Name", id: "5903df41-3a16-48eb-bc62-8ab1db15fa22", value: "Rita"});
CREATE (n:DResource {storedFilename: "Rita-076b60ed-085f-4444-a07c-c11531eb319b.tmp", imageFormat: "", filename: "Rita.tmp", id: "c02b0291-da13-4ea4-af36-776e5485d815"});
CREATE (n:Descriptor {name: "File", id: "fe5019c1-5ec6-4e63-8eea-ac350c690660", value: "Rita.tmp"});
CREATE (n:Object {position: [], id: "16c8d090-e0a2-4519-9b19-21a150ec1975"});
CREATE (n:Descriptor {name: "Name", id: "441b25d6-6bd2-4930-a8cf-230f3e233221", value: "Bratislava"});
CREATE (n:Object {position: [], id: "03d9ab33-4354-4283-a2be-789655e56bff"});
CREATE (n:Descriptor {name: "Name", id: "b403fd54-8ce4-4116-a62e-76b5694d3521", value: "Sara"});
CREATE (n:DResource {storedFilename: "Sara-90795388-ef0a-45b7-bb95-5e3ce839d0b3.tmp", imageFormat: "", filename: "Sara.tmp", id: "31642974-3af7-4f3c-a21b-d2c461b3ca2e"});
CREATE (n:Descriptor {name: "File", id: "c75c7eb4-98ce-4045-bd93-7a84c9ca8f5b", value: "Sara.tmp"});
CREATE (n:Object {position: [], id: "d9166727-9317-426b-8509-fd9e01ee73e3"});
CREATE (n:Descriptor {name: "Name", id: "a0f2fb44-3eb4-4953-aa31-e9e50b8eb845", value: "Simon"});
CREATE (n:DResource {storedFilename: "Simon-8b7b80ef-9abf-4080-9055-54c0644fb6fe.tmp", imageFormat: "", filename: "Simon.tmp", id: "947f5159-7ebe-4ba2-84f3-b7c9535e1cd4"});
CREATE (n:Descriptor {name: "File", id: "0caf5bdb-6404-4be8-aba0-79fbaea8c0c3", value: "Simon.tmp"});
CREATE (n:Object {position: [], id: "c039a7db-23e0-4976-a7ff-a744edd86850"});
CREATE (n:Descriptor {name: "Name", id: "fbd24ce1-9b3b-4eff-8a1f-0a1580821c8d", value: "Bakery"});
CREATE (n:Object {position: [], id: "5fba5d35-c70c-4a95-9631-1f61fdd268cb"});
CREATE (n:Descriptor {name: "Name", id: "6a65a655-dad8-482b-b6e3-cb972d8a4fd7", value: "Tom"});
CREATE (n:DResource {storedFilename: "Tom-1159c998-32fb-4d48-b544-5a798eedac9c.tmp", imageFormat: "", filename: "Tom.tmp", id: "a1d07797-baba-4411-ad4f-1d0aac57a5e2"});
CREATE (n:Descriptor {name: "File", id: "57b0b83b-1582-4867-bbc2-4cc49a2d2694", value: "Tom.tmp"});
CREATE (n:Object {position: [], id: "0c12bc40-f3dd-4d47-a425-6b123ec07325"});
CREATE (n:Descriptor {name: "Name", id: "025d0166-abcb-4bb9-9be2-2110486c0310", value: "Zoe"});
CREATE (n:DResource {storedFilename: "Zoe-f596bf78-e3d8-4e16-a9b8-83717ecf4e23.tmp", imageFormat: "", filename: "Zoe.tmp", id: "887e5f76-a4d4-4f66-811c-c03ab31e21cd"});
CREATE (n:Descriptor {name: "File", id: "f6d36b74-2fc7-4e01-b28b-641b2b7e35af", value: "Zoe.tmp"});
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "4ca16ea8-1996-4302-b2ad-e0ef91f63c86"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "5f3779d8-801c-4161-af3a-4a17358c7c59"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "4ca16ea8-1996-4302-b2ad-e0ef91f63c86" AND b.id = "5f3779d8-801c-4161-af3a-4a17358c7c59"
CREATE (a)-[:MEMBER_OF {id: "d61c6bd2-1c05-45ce-992c-7a828779d1e0"}]->(b);
MATCH (a), (b) WHERE a.id = "5f3779d8-801c-4161-af3a-4a17358c7c59" AND b.id = "e1e75fdd-21dd-490e-b4c8-cc26753df660"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "4ca16ea8-1996-4302-b2ad-e0ef91f63c86" AND b.id = "b1111978-9dd5-469f-856b-dfcadaefc8b9"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "0fc4c135-bfa2-4ac0-bd23-d4acaeedca53"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "831f2639-459b-4471-b0fa-f98866a23bfd"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "0fc4c135-bfa2-4ac0-bd23-d4acaeedca53" AND b.id = "831f2639-459b-4471-b0fa-f98866a23bfd"
CREATE (a)-[:MEMBER_OF {id: "bc03b27b-f3f3-47ab-90bd-d611c8332f8e"}]->(b);
MATCH (a), (b) WHERE a.id = "831f2639-459b-4471-b0fa-f98866a23bfd" AND b.id = "8f423e37-5975-40e2-9b71-c272366f30a4"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "0fc4c135-bfa2-4ac0-bd23-d4acaeedca53" AND b.id = "b8fdfee4-ef23-48e4-8c73-560a12f8d24a"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "b274e751-7cc3-480b-8aa2-59134ce46862"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "45efcce7-a92b-4aea-a785-e3c598b44f4a"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "b274e751-7cc3-480b-8aa2-59134ce46862" AND b.id = "45efcce7-a92b-4aea-a785-e3c598b44f4a"
CREATE (a)-[:MEMBER_OF {id: "cc8fbf13-6d8f-4bd5-bdec-181692ab4fa6"}]->(b);
MATCH (a), (b) WHERE a.id = "45efcce7-a92b-4aea-a785-e3c598b44f4a" AND b.id = "742dd5b0-085f-44f4-ace2-7b89638747ff"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "b274e751-7cc3-480b-8aa2-59134ce46862" AND b.id = "429dee63-f4cc-4f9d-94de-06b0b9ca545f"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "94d735fe-db72-4861-9ea6-5a51962e5936"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "94d735fe-db72-4861-9ea6-5a51962e5936" AND b.id = "45efcce7-a92b-4aea-a785-e3c598b44f4a"
CREATE (a)-[:MEMBER_OF {id: "beee080e-24c7-4621-ad5f-90e0ef82b23c"}]->(b);
MATCH (a), (b) WHERE a.id = "94d735fe-db72-4861-9ea6-5a51962e5936" AND b.id = "24348bbc-d0eb-4526-b78c-12605237414b"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "ddafb0c6-9ba1-47ce-95de-415839048d98"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "ddafb0c6-9ba1-47ce-95de-415839048d98" AND b.id = "45efcce7-a92b-4aea-a785-e3c598b44f4a"
CREATE (a)-[:MEMBER_OF {id: "326e7852-48cb-4500-ad3f-b2c4a3cb1cbe"}]->(b);
MATCH (a), (b) WHERE a.id = "ddafb0c6-9ba1-47ce-95de-415839048d98" AND b.id = "fff78beb-4851-4c94-a971-d3e0da1544d4"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "49d0c84e-9ae9-494d-95b3-d725a6335fcc"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "49d0c84e-9ae9-494d-95b3-d725a6335fcc" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "376f4dba-6f6f-4c02-8589-ac6142833d1e"}]->(b);
MATCH (a), (b) WHERE a.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8" AND b.id = "0c6764cf-3d68-41bb-802c-4585af2d8ac4"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "49d0c84e-9ae9-494d-95b3-d725a6335fcc" AND b.id = "88a427e0-f2ab-4f75-afff-555d4407e471"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8" AND b.id = "97246317-c062-4072-a71d-a5b559dd78c5"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "49d0c84e-9ae9-494d-95b3-d725a6335fcc" AND b.id = "72feaffb-fcee-4ca9-b3bf-c4eb2390ec75"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "72feaffb-fcee-4ca9-b3bf-c4eb2390ec75" AND b.id = "f369a64e-7f24-4263-9f2c-decc760bc003"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "92868009-deeb-4eb8-88fa-6197254faef4"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "92868009-deeb-4eb8-88fa-6197254faef4" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "66384b3b-7b13-4a99-9559-113ad1984231"}]->(b);
MATCH (a), (b) WHERE a.id = "92868009-deeb-4eb8-88fa-6197254faef4" AND b.id = "bba75caa-beba-4bac-9123-72e3b7af0b95"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "92868009-deeb-4eb8-88fa-6197254faef4" AND b.id = "369434eb-3659-4f44-a2c0-0eca06c0abb9"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "369434eb-3659-4f44-a2c0-0eca06c0abb9" AND b.id = "0f8ff612-d8c8-443b-ab30-1abf041411cd"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "8d2441bd-8e61-4358-a40a-18e7dab88527"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "8d2441bd-8e61-4358-a40a-18e7dab88527" AND b.id = "831f2639-459b-4471-b0fa-f98866a23bfd"
CREATE (a)-[:MEMBER_OF {id: "76e443b5-01ea-429f-b777-81a64fde7155"}]->(b);
MATCH (a), (b) WHERE a.id = "8d2441bd-8e61-4358-a40a-18e7dab88527" AND b.id = "de47987a-1247-4b57-9183-7d91a37f39e6"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "94be0006-7f27-4dc0-a405-f021aea002d1"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "94be0006-7f27-4dc0-a405-f021aea002d1" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "b3eac326-a0f8-4b8b-bcf5-4010a06f67d7"}]->(b);
MATCH (a), (b) WHERE a.id = "94be0006-7f27-4dc0-a405-f021aea002d1" AND b.id = "faa31711-3dea-4a5a-9f52-d07772d1becb"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "94be0006-7f27-4dc0-a405-f021aea002d1" AND b.id = "d7a2774b-52d2-4177-8009-051cc521a5b3"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "d7a2774b-52d2-4177-8009-051cc521a5b3" AND b.id = "4f23391f-f6a7-4760-84c8-c15b52bf57bc"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "1f50b3bd-283a-4216-866d-46d5e74d2933"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "1f50b3bd-283a-4216-866d-46d5e74d2933" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "1544e4ca-c3b7-4176-8b50-af7738f9ae91"}]->(b);
MATCH (a), (b) WHERE a.id = "1f50b3bd-283a-4216-866d-46d5e74d2933" AND b.id = "d976fbd5-5002-4f19-be47-987124d1db81"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "1f50b3bd-283a-4216-866d-46d5e74d2933" AND b.id = "a0e792fe-d225-4841-8173-8806c93759ed"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "a0e792fe-d225-4841-8173-8806c93759ed" AND b.id = "b95aaf42-c319-40aa-aa0a-d7cf286a3ed0"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "afdfff74-f0d2-4d2f-b4ca-3e4fb7a6a58f"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "afdfff74-f0d2-4d2f-b4ca-3e4fb7a6a58f" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "c3d7e318-91ab-4dc1-8d0e-6d76094aa4c6"}]->(b);
MATCH (a), (b) WHERE a.id = "afdfff74-f0d2-4d2f-b4ca-3e4fb7a6a58f" AND b.id = "c3bb39a0-481a-432d-850d-019f46cc3c68"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "afdfff74-f0d2-4d2f-b4ca-3e4fb7a6a58f" AND b.id = "9b5dac52-78ab-46b4-addd-4fc6432673cd"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "9b5dac52-78ab-46b4-addd-4fc6432673cd" AND b.id = "c0b6c7e4-a368-42cd-9cbb-608cc39344a7"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "fd1cbafd-ddc9-47c0-81cd-6b5836f67ca4"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "fd1cbafd-ddc9-47c0-81cd-6b5836f67ca4" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "ae124c87-aa2d-421e-aba2-8c2614a01ee8"}]->(b);
MATCH (a), (b) WHERE a.id = "fd1cbafd-ddc9-47c0-81cd-6b5836f67ca4" AND b.id = "fb634b5f-c884-483b-95d6-7df54f1b235f"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "fd1cbafd-ddc9-47c0-81cd-6b5836f67ca4" AND b.id = "629e0bcf-e053-40ff-91d4-ef79dd7f95ee"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "629e0bcf-e053-40ff-91d4-ef79dd7f95ee" AND b.id = "e5ffc020-1899-4f2c-9769-823ea513308e"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "d94060b2-b8ec-4fd1-a232-a8f5e4750fed"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "d94060b2-b8ec-4fd1-a232-a8f5e4750fed" AND b.id = "5f3779d8-801c-4161-af3a-4a17358c7c59"
CREATE (a)-[:MEMBER_OF {id: "227d8a2c-9225-4b5a-9f7a-b7ed11afb4b2"}]->(b);
MATCH (a), (b) WHERE a.id = "d94060b2-b8ec-4fd1-a232-a8f5e4750fed" AND b.id = "3ef26525-f63a-47d9-9b96-739f4398e97f"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "01d34d74-2f74-4ba5-97b6-de52e54db6cf"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "01d34d74-2f74-4ba5-97b6-de52e54db6cf" AND b.id = "831f2639-459b-4471-b0fa-f98866a23bfd"
CREATE (a)-[:MEMBER_OF {id: "bd6a896d-1510-4b01-94a7-9c1cb8b96de5"}]->(b);
MATCH (a), (b) WHERE a.id = "01d34d74-2f74-4ba5-97b6-de52e54db6cf" AND b.id = "10c69f3c-3b0c-4594-b59d-61741463ec4b"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "23ba876b-35cc-4fc1-a433-b4497b800882"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "23ba876b-35cc-4fc1-a433-b4497b800882" AND b.id = "45efcce7-a92b-4aea-a785-e3c598b44f4a"
CREATE (a)-[:MEMBER_OF {id: "d9d28b70-9fd5-4594-86d1-e15d666c0da8"}]->(b);
MATCH (a), (b) WHERE a.id = "23ba876b-35cc-4fc1-a433-b4497b800882" AND b.id = "b85f0a14-1c77-4f6f-878e-90de758a4a8a"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "ed1b56e3-86ac-4e88-b28b-9ef7c1b97c5e"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "ed1b56e3-86ac-4e88-b28b-9ef7c1b97c5e" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "46d82ed8-f087-4016-b2ce-9522679a8664"}]->(b);
MATCH (a), (b) WHERE a.id = "ed1b56e3-86ac-4e88-b28b-9ef7c1b97c5e" AND b.id = "eae6effb-6fdc-486b-842f-20f498076a7d"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "ed1b56e3-86ac-4e88-b28b-9ef7c1b97c5e" AND b.id = "f6fc5ee4-2ca0-4736-bac1-167841956cff"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "f6fc5ee4-2ca0-4736-bac1-167841956cff" AND b.id = "a7892a4c-87ab-49e8-b8cb-9a924d5c17db"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "b129b455-38c4-4521-929e-3ff5784471f6"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "b129b455-38c4-4521-929e-3ff5784471f6" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "f31d2766-da1c-42dc-ac56-6725bfa9ecd3"}]->(b);
MATCH (a), (b) WHERE a.id = "b129b455-38c4-4521-929e-3ff5784471f6" AND b.id = "3038f1b1-5f37-46fe-bd86-a67ca91be330"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "b129b455-38c4-4521-929e-3ff5784471f6" AND b.id = "55c5f2a9-a81c-4493-95d6-def118a69b3e"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "55c5f2a9-a81c-4493-95d6-def118a69b3e" AND b.id = "767981cf-77a2-47e9-b758-a0dba9ae3a18"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "d2b0298d-6afc-4412-883b-a191973b8241"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "d2b0298d-6afc-4412-883b-a191973b8241" AND b.id = "45efcce7-a92b-4aea-a785-e3c598b44f4a"
CREATE (a)-[:MEMBER_OF {id: "0876faa3-9124-4315-aef5-e9f88cbcefa5"}]->(b);
MATCH (a), (b) WHERE a.id = "d2b0298d-6afc-4412-883b-a191973b8241" AND b.id = "ca4b500a-5f6a-4279-86ba-630e31e87006"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "a06f771d-de1a-4ffa-8cfb-93527e85ce75"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "a06f771d-de1a-4ffa-8cfb-93527e85ce75" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "a5adeea5-90cd-4182-a18d-013bb9842d9c"}]->(b);
MATCH (a), (b) WHERE a.id = "a06f771d-de1a-4ffa-8cfb-93527e85ce75" AND b.id = "7c405c21-7b13-4ef4-8901-73a198eb82b5"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "a06f771d-de1a-4ffa-8cfb-93527e85ce75" AND b.id = "0436e34e-18e5-4e0f-ad8b-eb8119d66565"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "0436e34e-18e5-4e0f-ad8b-eb8119d66565" AND b.id = "a56fda6c-df6d-480b-b3e2-3ce0b11cd264"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "8d5de1b7-e6ab-43f9-8fc1-deaab75ad033"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "8d5de1b7-e6ab-43f9-8fc1-deaab75ad033" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "53ecb05b-f67e-4e4d-ad5a-89be6cac528b"}]->(b);
MATCH (a), (b) WHERE a.id = "8d5de1b7-e6ab-43f9-8fc1-deaab75ad033" AND b.id = "4c1c57b7-069a-49f3-bc48-f46905e68dc9"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "8d5de1b7-e6ab-43f9-8fc1-deaab75ad033" AND b.id = "3ef2431f-cda1-46fd-b264-a8002f82d1b7"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "3ef2431f-cda1-46fd-b264-a8002f82d1b7" AND b.id = "b824be24-e5d4-4df4-8a07-f5c7871b4c8b"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "05fe86e5-25df-44fb-8c29-c02d4785617a"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "05fe86e5-25df-44fb-8c29-c02d4785617a" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "f997ad22-a7f2-4c90-af24-06d1aac84ee9"}]->(b);
MATCH (a), (b) WHERE a.id = "05fe86e5-25df-44fb-8c29-c02d4785617a" AND b.id = "aa25b2c3-e6db-4006-837e-8b8dbfd24831"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "05fe86e5-25df-44fb-8c29-c02d4785617a" AND b.id = "312b735f-f0d6-4b13-a897-9cf4f2c6ea9f"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "312b735f-f0d6-4b13-a897-9cf4f2c6ea9f" AND b.id = "2a1e5939-8aba-4a12-a11e-9a88a6eacf1c"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "30dccb5e-779b-4ed1-89b7-b2104c4f0cfb"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "30dccb5e-779b-4ed1-89b7-b2104c4f0cfb" AND b.id = "5f3779d8-801c-4161-af3a-4a17358c7c59"
CREATE (a)-[:MEMBER_OF {id: "f59fa40b-0960-4099-9e6e-e2ad579a9d77"}]->(b);
MATCH (a), (b) WHERE a.id = "30dccb5e-779b-4ed1-89b7-b2104c4f0cfb" AND b.id = "69743eda-42ac-49bd-bc31-785be6f1602d"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "ee015145-df29-482b-9714-8da29c995e3f"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "ee015145-df29-482b-9714-8da29c995e3f" AND b.id = "831f2639-459b-4471-b0fa-f98866a23bfd"
CREATE (a)-[:MEMBER_OF {id: "23dc9277-d1c7-43ee-b3ea-6550bff08882"}]->(b);
MATCH (a), (b) WHERE a.id = "ee015145-df29-482b-9714-8da29c995e3f" AND b.id = "9e25a694-9588-48bb-b0ef-929aa67f412e"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "0f578787-4c5b-4c78-8bda-609ba3059940"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "0f578787-4c5b-4c78-8bda-609ba3059940" AND b.id = "45efcce7-a92b-4aea-a785-e3c598b44f4a"
CREATE (a)-[:MEMBER_OF {id: "6da730da-d595-4b37-9027-03b916e8f3f3"}]->(b);
MATCH (a), (b) WHERE a.id = "0f578787-4c5b-4c78-8bda-609ba3059940" AND b.id = "766e577c-c1e8-45b8-9d0e-95f5db559423"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "d860ddd7-8029-4567-8695-e54fb4d0537a"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "d860ddd7-8029-4567-8695-e54fb4d0537a" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "fa40fb7d-bf58-4baf-9ef9-972c739d62a8"}]->(b);
MATCH (a), (b) WHERE a.id = "d860ddd7-8029-4567-8695-e54fb4d0537a" AND b.id = "d6b68077-b5bd-404e-ad57-efea76adb062"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "d860ddd7-8029-4567-8695-e54fb4d0537a" AND b.id = "64e451b4-3369-4d31-9ec3-2619fe75d018"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "64e451b4-3369-4d31-9ec3-2619fe75d018" AND b.id = "915c77e6-4ab3-441d-8fce-7ec53fe00555"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "9952a9f9-b4e8-4c57-bb9d-e94cad4ab14d"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "9952a9f9-b4e8-4c57-bb9d-e94cad4ab14d" AND b.id = "45efcce7-a92b-4aea-a785-e3c598b44f4a"
CREATE (a)-[:MEMBER_OF {id: "1d1da40e-6b14-4e29-bc12-b17116eca029"}]->(b);
MATCH (a), (b) WHERE a.id = "9952a9f9-b4e8-4c57-bb9d-e94cad4ab14d" AND b.id = "d0be139a-359e-4014-99c9-50f0516d4e60"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "26660adc-5464-4c1f-8552-4a14be154220"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "26660adc-5464-4c1f-8552-4a14be154220" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "38e64fed-d438-45cc-9ca0-923e21565b9d"}]->(b);
MATCH (a), (b) WHERE a.id = "26660adc-5464-4c1f-8552-4a14be154220" AND b.id = "cd9eb96b-d2c7-4239-8b4d-0927654bb378"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "26660adc-5464-4c1f-8552-4a14be154220" AND b.id = "6710cede-8d35-4f38-9956-20b773c5355e"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "6710cede-8d35-4f38-9956-20b773c5355e" AND b.id = "b92d9d96-8a2c-4128-a090-8378cf0178a1"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "50c1a29b-ede7-4dee-8bab-99556949ed97"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "50c1a29b-ede7-4dee-8bab-99556949ed97" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "15e5c67e-a867-4d05-8be3-378436478b1c"}]->(b);
MATCH (a), (b) WHERE a.id = "50c1a29b-ede7-4dee-8bab-99556949ed97" AND b.id = "136e92c4-ef73-4d93-a338-1be7f911d8d6"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "50c1a29b-ede7-4dee-8bab-99556949ed97" AND b.id = "4fe3ed5c-0241-4e49-bf78-c90d18bf7c2c"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "4fe3ed5c-0241-4e49-bf78-c90d18bf7c2c" AND b.id = "eec9cb82-7c9b-4e66-a2aa-601d52e44b24"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "7b9d3af7-f336-4464-8c22-e3a4899f3fb2"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "7b9d3af7-f336-4464-8c22-e3a4899f3fb2" AND b.id = "831f2639-459b-4471-b0fa-f98866a23bfd"
CREATE (a)-[:MEMBER_OF {id: "a3e452ce-ce8c-4522-aa89-0a1a3945e323"}]->(b);
MATCH (a), (b) WHERE a.id = "7b9d3af7-f336-4464-8c22-e3a4899f3fb2" AND b.id = "9e450c6f-209f-4fe9-b2bc-5275ccaa629d"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "40bbd1fb-d85d-4c69-b17a-f908938cd652"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "40bbd1fb-d85d-4c69-b17a-f908938cd652" AND b.id = "45efcce7-a92b-4aea-a785-e3c598b44f4a"
CREATE (a)-[:MEMBER_OF {id: "064dd4b9-79fe-4d54-a8ef-75aa55a5f694"}]->(b);
MATCH (a), (b) WHERE a.id = "40bbd1fb-d85d-4c69-b17a-f908938cd652" AND b.id = "01bf9c5f-47a9-4ccd-a532-3225ca11c2dc"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "63025fdb-e9d1-4b66-9d59-f959cefeb788"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "63025fdb-e9d1-4b66-9d59-f959cefeb788" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "8fd779ba-3d62-4f61-83e6-787dd00c2894"}]->(b);
MATCH (a), (b) WHERE a.id = "63025fdb-e9d1-4b66-9d59-f959cefeb788" AND b.id = "e681042d-fe27-41f0-8f83-cf3526962a45"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "63025fdb-e9d1-4b66-9d59-f959cefeb788" AND b.id = "71b240b6-9287-463f-a009-4d3351553484"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "71b240b6-9287-463f-a009-4d3351553484" AND b.id = "4666776c-8c82-489f-b18f-e914ed6d93bb"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "7ce79b68-4f24-48e5-8af6-cc0f00e1760e"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "7ce79b68-4f24-48e5-8af6-cc0f00e1760e" AND b.id = "45efcce7-a92b-4aea-a785-e3c598b44f4a"
CREATE (a)-[:MEMBER_OF {id: "29bc0814-fbdb-4069-a792-e6fe0506820f"}]->(b);
MATCH (a), (b) WHERE a.id = "7ce79b68-4f24-48e5-8af6-cc0f00e1760e" AND b.id = "d337a7e3-2135-40ac-b9f4-8ee1c1dca81f"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "d50f4b84-82a6-4b64-b335-20d4558c034a"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "d50f4b84-82a6-4b64-b335-20d4558c034a" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "f467b81e-5e47-4b8c-865f-c47f2a007412"}]->(b);
MATCH (a), (b) WHERE a.id = "d50f4b84-82a6-4b64-b335-20d4558c034a" AND b.id = "a80684ce-18f5-423b-88db-ee11ce6f987e"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "d50f4b84-82a6-4b64-b335-20d4558c034a" AND b.id = "a9e7abba-3b75-447b-be44-8c7c9aba2b33"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "a9e7abba-3b75-447b-be44-8c7c9aba2b33" AND b.id = "d7f8f43b-7f3c-48ac-9b6c-bedcc57bc41e"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "a8cb68eb-c5d7-47db-bc80-80671a3e8ba1"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "a8cb68eb-c5d7-47db-bc80-80671a3e8ba1" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "807014e2-b645-4c9e-be69-a35c479f48c2"}]->(b);
MATCH (a), (b) WHERE a.id = "a8cb68eb-c5d7-47db-bc80-80671a3e8ba1" AND b.id = "60e15e27-ca52-4e2e-a1bc-66ef0f4f25d6"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "a8cb68eb-c5d7-47db-bc80-80671a3e8ba1" AND b.id = "a8397fb3-b058-4bc7-abcc-afa074f3830f"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "a8397fb3-b058-4bc7-abcc-afa074f3830f" AND b.id = "7ecd5f03-b221-4e47-8af0-47932b50ac3b"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "4084a781-bf45-4dfa-b406-4a8a82122f33"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "4084a781-bf45-4dfa-b406-4a8a82122f33" AND b.id = "45efcce7-a92b-4aea-a785-e3c598b44f4a"
CREATE (a)-[:MEMBER_OF {id: "9d3694de-e748-4b33-ad2c-9fb2dfc0e3b1"}]->(b);
MATCH (a), (b) WHERE a.id = "4084a781-bf45-4dfa-b406-4a8a82122f33" AND b.id = "987c22c7-0a12-4a1c-b4e5-b2dec3ea48c4"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "dce8b130-a524-4816-af8b-1434f95a9339"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "dce8b130-a524-4816-af8b-1434f95a9339" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "e6383627-447c-481f-baa2-7bc6a9c6fd90"}]->(b);
MATCH (a), (b) WHERE a.id = "dce8b130-a524-4816-af8b-1434f95a9339" AND b.id = "fe8c15cd-efed-438a-8a6f-adc6fbcff297"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "dce8b130-a524-4816-af8b-1434f95a9339" AND b.id = "49d67b74-3d96-4597-a366-26e9d6f0d9a2"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "49d67b74-3d96-4597-a366-26e9d6f0d9a2" AND b.id = "73651c48-3fb9-49dc-bde4-7affef806ad5"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "dccf82c6-4bd4-44ae-9ab1-84cd33773d94"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "dccf82c6-4bd4-44ae-9ab1-84cd33773d94" AND b.id = "45efcce7-a92b-4aea-a785-e3c598b44f4a"
CREATE (a)-[:MEMBER_OF {id: "24b3274c-b79e-4efb-a1c6-75eb2c69d0f6"}]->(b);
MATCH (a), (b) WHERE a.id = "dccf82c6-4bd4-44ae-9ab1-84cd33773d94" AND b.id = "1cf8e1ca-e4c7-48f6-a5db-dd422c449d2b"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "baf8c6ef-602f-4da6-bd18-3064c707c70f"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "baf8c6ef-602f-4da6-bd18-3064c707c70f" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "86893b65-d2e3-4eee-819e-933bcc6d8d4c"}]->(b);
MATCH (a), (b) WHERE a.id = "baf8c6ef-602f-4da6-bd18-3064c707c70f" AND b.id = "38cb24bf-2731-491d-abe1-b3cc018e739a"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "baf8c6ef-602f-4da6-bd18-3064c707c70f" AND b.id = "19ec7d87-5b80-4e54-a3b4-6fc234b7ae53"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "19ec7d87-5b80-4e54-a3b4-6fc234b7ae53" AND b.id = "c042d3a0-aa59-43fb-88ec-b41ff6526dd8"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "e86332b6-d02f-44b2-93f7-e8e420880c51"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "e86332b6-d02f-44b2-93f7-e8e420880c51" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "7afeb5b1-fa90-4076-b3f8-adcf5dcbab83"}]->(b);
MATCH (a), (b) WHERE a.id = "e86332b6-d02f-44b2-93f7-e8e420880c51" AND b.id = "5903df41-3a16-48eb-bc62-8ab1db15fa22"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "e86332b6-d02f-44b2-93f7-e8e420880c51" AND b.id = "fe5019c1-5ec6-4e63-8eea-ac350c690660"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "fe5019c1-5ec6-4e63-8eea-ac350c690660" AND b.id = "c02b0291-da13-4ea4-af36-776e5485d815"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "16c8d090-e0a2-4519-9b19-21a150ec1975"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "16c8d090-e0a2-4519-9b19-21a150ec1975" AND b.id = "831f2639-459b-4471-b0fa-f98866a23bfd"
CREATE (a)-[:MEMBER_OF {id: "07b4dab9-5d2c-4b17-b57a-99aede839278"}]->(b);
MATCH (a), (b) WHERE a.id = "16c8d090-e0a2-4519-9b19-21a150ec1975" AND b.id = "441b25d6-6bd2-4930-a8cf-230f3e233221"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "03d9ab33-4354-4283-a2be-789655e56bff"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "03d9ab33-4354-4283-a2be-789655e56bff" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "5b64df10-09a8-499b-b86c-6798c89d64b2"}]->(b);
MATCH (a), (b) WHERE a.id = "03d9ab33-4354-4283-a2be-789655e56bff" AND b.id = "b403fd54-8ce4-4116-a62e-76b5694d3521"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "03d9ab33-4354-4283-a2be-789655e56bff" AND b.id = "c75c7eb4-98ce-4045-bd93-7a84c9ca8f5b"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "c75c7eb4-98ce-4045-bd93-7a84c9ca8f5b" AND b.id = "31642974-3af7-4f3c-a21b-d2c461b3ca2e"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "d9166727-9317-426b-8509-fd9e01ee73e3"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "d9166727-9317-426b-8509-fd9e01ee73e3" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "73e2e9b1-3ee2-4949-b044-b60651d510e8"}]->(b);
MATCH (a), (b) WHERE a.id = "d9166727-9317-426b-8509-fd9e01ee73e3" AND b.id = "a0f2fb44-3eb4-4953-aa31-e9e50b8eb845"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "d9166727-9317-426b-8509-fd9e01ee73e3" AND b.id = "0caf5bdb-6404-4be8-aba0-79fbaea8c0c3"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "0caf5bdb-6404-4be8-aba0-79fbaea8c0c3" AND b.id = "947f5159-7ebe-4ba2-84f3-b7c9535e1cd4"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "c039a7db-23e0-4976-a7ff-a744edd86850"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "c039a7db-23e0-4976-a7ff-a744edd86850" AND b.id = "45efcce7-a92b-4aea-a785-e3c598b44f4a"
CREATE (a)-[:MEMBER_OF {id: "c84bdfbd-74c6-4228-a507-43bc5704a5e4"}]->(b);
MATCH (a), (b) WHERE a.id = "c039a7db-23e0-4976-a7ff-a744edd86850" AND b.id = "fbd24ce1-9b3b-4eff-8a1f-0a1580821c8d"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "5fba5d35-c70c-4a95-9631-1f61fdd268cb"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "5fba5d35-c70c-4a95-9631-1f61fdd268cb" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "abd97c40-f5b1-41a2-9369-3105a60294a4"}]->(b);
MATCH (a), (b) WHERE a.id = "5fba5d35-c70c-4a95-9631-1f61fdd268cb" AND b.id = "6a65a655-dad8-482b-b6e3-cb972d8a4fd7"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "5fba5d35-c70c-4a95-9631-1f61fdd268cb" AND b.id = "57b0b83b-1582-4867-bbc2-4cc49a2d2694"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "57b0b83b-1582-4867-bbc2-4cc49a2d2694" AND b.id = "a1d07797-baba-4411-ad4f-1d0aac57a5e2"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "7eb35647-7b56-4121-890c-7d239c293cb0" AND b.id = "0c12bc40-f3dd-4d47-a425-6b123ec07325"
CREATE (a)-[:CONTAINS ]->(b);
MATCH (a), (b) WHERE a.id = "0c12bc40-f3dd-4d47-a425-6b123ec07325" AND b.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8"
CREATE (a)-[:MEMBER_OF {id: "874b7ef5-37fe-404b-ac68-dadf5972016d"}]->(b);
MATCH (a), (b) WHERE a.id = "0c12bc40-f3dd-4d47-a425-6b123ec07325" AND b.id = "025d0166-abcb-4bb9-9be2-2110486c0310"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "0c12bc40-f3dd-4d47-a425-6b123ec07325" AND b.id = "f6d36b74-2fc7-4e01-b28b-641b2b7e35af"
CREATE (a)-[:HAS_DESCRIPTOR ]->(b);
MATCH (a), (b) WHERE a.id = "f6d36b74-2fc7-4e01-b28b-641b2b7e35af" AND b.id = "887e5f76-a4d4-4f66-811c-c03ab31e21cd"
CREATE (a)-[:HAS_RESOURCE ]->(b);
MATCH (a), (b) WHERE a.id = "5f3779d8-801c-4161-af3a-4a17358c7c59" AND b.id = "831f2639-459b-4471-b0fa-f98866a23bfd"
CREATE (a)-[:RELATED_TO {comment: "", label: "contains", id: "859e6658-a2bf-4155-aa00-2d98ff42114a"}]->(b);
MATCH (a), (b) WHERE a.id = "4ca16ea8-1996-4302-b2ad-e0ef91f63c86" AND b.id = "0fc4c135-bfa2-4ac0-bd23-d4acaeedca53"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "24a1174a-b242-4d99-a366-f84a8fdeb064"}]->(b);
MATCH (a), (b) WHERE a.id = "831f2639-459b-4471-b0fa-f98866a23bfd" AND b.id = "45efcce7-a92b-4aea-a785-e3c598b44f4a"
CREATE (a)-[:RELATED_TO {comment: "", label: "contains", id: "04b7910b-d1b3-474c-b056-7ed7cdb610a2"}]->(b);
MATCH (a), (b) WHERE a.id = "0fc4c135-bfa2-4ac0-bd23-d4acaeedca53" AND b.id = "b274e751-7cc3-480b-8aa2-59134ce46862"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "f6389d7e-3d01-4ac7-988e-d198d5e5b3e0"}]->(b);
MATCH (a), (b) WHERE a.id = "b274e751-7cc3-480b-8aa2-59134ce46862" AND b.id = "94d735fe-db72-4861-9ea6-5a51962e5936"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "competes_with", id: "91b27157-a969-43e9-86ee-97283a473772"}]->(b);
MATCH (a), (b) WHERE a.id = "b274e751-7cc3-480b-8aa2-59134ce46862" AND b.id = "ddafb0c6-9ba1-47ce-95de-415839048d98"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "competes_with", id: "6dc1ec5c-5fc3-46f5-a0c9-d66664d88306"}]->(b);
MATCH (a), (b) WHERE a.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8" AND b.id = "45efcce7-a92b-4aea-a785-e3c598b44f4a"
CREATE (a)-[:RELATED_TO {comment: "", label: "employee_of", id: "89e93907-14fd-488a-8ca5-cbca1acfc548"}]->(b);
MATCH (a), (b) WHERE a.id = "49d0c84e-9ae9-494d-95b3-d725a6335fcc" AND b.id = "b274e751-7cc3-480b-8aa2-59134ce46862"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "306b0ea9-2ec9-4d83-af21-7a1019894f57"}]->(b);
MATCH (a), (b) WHERE a.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8" AND b.id = "5f3779d8-801c-4161-af3a-4a17358c7c59"
CREATE (a)-[:RELATED_TO {comment: "", label: "citizen_of", id: "aadafccc-0484-4405-9117-f3a9d13e1ea5"}]->(b);
MATCH (a), (b) WHERE a.id = "49d0c84e-9ae9-494d-95b3-d725a6335fcc" AND b.id = "4ca16ea8-1996-4302-b2ad-e0ef91f63c86"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "d40af0c4-998b-4ec4-a9fb-e8588975934c"}]->(b);
MATCH (a), (b) WHERE a.id = "c2a61508-1c01-403a-8daa-6bcbf667c0d8" AND b.id = "831f2639-459b-4471-b0fa-f98866a23bfd"
CREATE (a)-[:RELATED_TO {comment: "", label: "lives_in", id: "0be7829b-fb1a-4044-a9a6-5b2f534d50b7"}]->(b);
MATCH (a), (b) WHERE a.id = "49d0c84e-9ae9-494d-95b3-d725a6335fcc" AND b.id = "0fc4c135-bfa2-4ac0-bd23-d4acaeedca53"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "b85f611e-8187-42b1-971d-f3a01fabfa95"}]->(b);
MATCH (a), (b) WHERE a.id = "49d0c84e-9ae9-494d-95b3-d725a6335fcc" AND b.id = "92868009-deeb-4eb8-88fa-6197254faef4"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "friend_of", id: "d30cfb64-16fd-4a3d-a710-962ffbe3307d"}]->(b);
MATCH (a), (b) WHERE a.id = "92868009-deeb-4eb8-88fa-6197254faef4" AND b.id = "b274e751-7cc3-480b-8aa2-59134ce46862"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "13d9901b-3292-49e7-b775-30342c7a3410"}]->(b);
MATCH (a), (b) WHERE a.id = "92868009-deeb-4eb8-88fa-6197254faef4" AND b.id = "4ca16ea8-1996-4302-b2ad-e0ef91f63c86"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "b3ff65c8-dad7-49e7-80e8-254d65bf3565"}]->(b);
MATCH (a), (b) WHERE a.id = "92868009-deeb-4eb8-88fa-6197254faef4" AND b.id = "8d2441bd-8e61-4358-a40a-18e7dab88527"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "f521bceb-5e0e-4cf5-9e0e-4c7df77c2b47"}]->(b);
MATCH (a), (b) WHERE a.id = "92868009-deeb-4eb8-88fa-6197254faef4" AND b.id = "94be0006-7f27-4dc0-a405-f021aea002d1"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "friend_of", id: "e3e22598-7c8f-4f9c-9ede-0c69b037b0f2"}]->(b);
MATCH (a), (b) WHERE a.id = "92868009-deeb-4eb8-88fa-6197254faef4" AND b.id = "1f50b3bd-283a-4216-866d-46d5e74d2933"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "friend_of", id: "7948b431-4349-496a-adb1-d10d6720261a"}]->(b);
MATCH (a), (b) WHERE a.id = "0fc4c135-bfa2-4ac0-bd23-d4acaeedca53" AND b.id = "94d735fe-db72-4861-9ea6-5a51962e5936"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "b4e713cd-3f55-471f-a7c2-3e0b466979f2"}]->(b);
MATCH (a), (b) WHERE a.id = "afdfff74-f0d2-4d2f-b4ca-3e4fb7a6a58f" AND b.id = "94d735fe-db72-4861-9ea6-5a51962e5936"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "357acdc8-88f7-4c81-bb63-044a62771463"}]->(b);
MATCH (a), (b) WHERE a.id = "afdfff74-f0d2-4d2f-b4ca-3e4fb7a6a58f" AND b.id = "4ca16ea8-1996-4302-b2ad-e0ef91f63c86"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "c32af45d-7a53-4f08-b3f5-c86943d461b1"}]->(b);
MATCH (a), (b) WHERE a.id = "afdfff74-f0d2-4d2f-b4ca-3e4fb7a6a58f" AND b.id = "0fc4c135-bfa2-4ac0-bd23-d4acaeedca53"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "9349bfee-2511-4d51-a1e1-ac44f4ce8f18"}]->(b);
MATCH (a), (b) WHERE a.id = "fd1cbafd-ddc9-47c0-81cd-6b5836f67ca4" AND b.id = "94d735fe-db72-4861-9ea6-5a51962e5936"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "41817f5c-ba62-4cd4-ad75-c69694335505"}]->(b);
MATCH (a), (b) WHERE a.id = "fd1cbafd-ddc9-47c0-81cd-6b5836f67ca4" AND b.id = "d94060b2-b8ec-4fd1-a232-a8f5e4750fed"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "af524a71-0a0f-4911-b21f-48d7d6290f58"}]->(b);
MATCH (a), (b) WHERE a.id = "fd1cbafd-ddc9-47c0-81cd-6b5836f67ca4" AND b.id = "01d34d74-2f74-4ba5-97b6-de52e54db6cf"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "01f188eb-5468-4d53-abfe-624518e361ad"}]->(b);
MATCH (a), (b) WHERE a.id = "4ca16ea8-1996-4302-b2ad-e0ef91f63c86" AND b.id = "8d2441bd-8e61-4358-a40a-18e7dab88527"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "0e13f0c7-2e2d-4c43-8ff0-9dbf76126d26"}]->(b);
MATCH (a), (b) WHERE a.id = "8d2441bd-8e61-4358-a40a-18e7dab88527" AND b.id = "23ba876b-35cc-4fc1-a433-b4497b800882"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "eabdb0ba-d232-4111-a3af-39df04ec1178"}]->(b);
MATCH (a), (b) WHERE a.id = "ed1b56e3-86ac-4e88-b28b-9ef7c1b97c5e" AND b.id = "23ba876b-35cc-4fc1-a433-b4497b800882"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "b0867ea4-a5f9-4d6d-8c14-3cf934e1ba12"}]->(b);
MATCH (a), (b) WHERE a.id = "ed1b56e3-86ac-4e88-b28b-9ef7c1b97c5e" AND b.id = "4ca16ea8-1996-4302-b2ad-e0ef91f63c86"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "562aa8cd-4f31-48b3-b2d1-d10f6f175cb8"}]->(b);
MATCH (a), (b) WHERE a.id = "ed1b56e3-86ac-4e88-b28b-9ef7c1b97c5e" AND b.id = "8d2441bd-8e61-4358-a40a-18e7dab88527"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "b40d54eb-39e8-49d1-8ca4-153266fd458c"}]->(b);
MATCH (a), (b) WHERE a.id = "b129b455-38c4-4521-929e-3ff5784471f6" AND b.id = "23ba876b-35cc-4fc1-a433-b4497b800882"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "38338f15-a7d0-439b-9ce7-c32a244d03dd"}]->(b);
MATCH (a), (b) WHERE a.id = "b129b455-38c4-4521-929e-3ff5784471f6" AND b.id = "4ca16ea8-1996-4302-b2ad-e0ef91f63c86"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "48b70f2d-58a9-49ad-9867-ffd1faaf15e1"}]->(b);
MATCH (a), (b) WHERE a.id = "b129b455-38c4-4521-929e-3ff5784471f6" AND b.id = "8d2441bd-8e61-4358-a40a-18e7dab88527"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "19a2397b-00c2-48f4-9b1e-ffe678f821c2"}]->(b);
MATCH (a), (b) WHERE a.id = "8d2441bd-8e61-4358-a40a-18e7dab88527" AND b.id = "d2b0298d-6afc-4412-883b-a191973b8241"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "a0a4d811-9714-4d63-b1f5-d5e8e8e3b795"}]->(b);
MATCH (a), (b) WHERE a.id = "a06f771d-de1a-4ffa-8cfb-93527e85ce75" AND b.id = "d2b0298d-6afc-4412-883b-a191973b8241"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "d0945883-65db-480d-a000-d3baa1b22c15"}]->(b);
MATCH (a), (b) WHERE a.id = "a06f771d-de1a-4ffa-8cfb-93527e85ce75" AND b.id = "4ca16ea8-1996-4302-b2ad-e0ef91f63c86"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "7f32aa69-4124-4453-a5fb-36ef779cc6a2"}]->(b);
MATCH (a), (b) WHERE a.id = "a06f771d-de1a-4ffa-8cfb-93527e85ce75" AND b.id = "8d2441bd-8e61-4358-a40a-18e7dab88527"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "aeeddbf3-5513-4898-94f2-756b388d1893"}]->(b);
MATCH (a), (b) WHERE a.id = "a06f771d-de1a-4ffa-8cfb-93527e85ce75" AND b.id = "8d5de1b7-e6ab-43f9-8fc1-deaab75ad033"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "friend_of", id: "3dc628e3-5b07-4183-8a63-0e731a036135"}]->(b);
MATCH (a), (b) WHERE a.id = "05fe86e5-25df-44fb-8c29-c02d4785617a" AND b.id = "d2b0298d-6afc-4412-883b-a191973b8241"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "7524b484-118d-47da-a8df-1ac1bb14a489"}]->(b);
MATCH (a), (b) WHERE a.id = "05fe86e5-25df-44fb-8c29-c02d4785617a" AND b.id = "30dccb5e-779b-4ed1-89b7-b2104c4f0cfb"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "fd091623-5be9-4e13-814b-cbedee2f89f4"}]->(b);
MATCH (a), (b) WHERE a.id = "05fe86e5-25df-44fb-8c29-c02d4785617a" AND b.id = "ee015145-df29-482b-9714-8da29c995e3f"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "361f8ade-4100-4244-be02-fead5c3e474d"}]->(b);
MATCH (a), (b) WHERE a.id = "05fe86e5-25df-44fb-8c29-c02d4785617a" AND b.id = "a06f771d-de1a-4ffa-8cfb-93527e85ce75"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "friend_of", id: "8a43bf0c-91ce-4bcb-aed5-1daf44de1eaa"}]->(b);
MATCH (a), (b) WHERE a.id = "d94060b2-b8ec-4fd1-a232-a8f5e4750fed" AND b.id = "01d34d74-2f74-4ba5-97b6-de52e54db6cf"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "0b751fce-c3b2-454d-a933-c46c6b0600ef"}]->(b);
MATCH (a), (b) WHERE a.id = "01d34d74-2f74-4ba5-97b6-de52e54db6cf" AND b.id = "ddafb0c6-9ba1-47ce-95de-415839048d98"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "227cfb54-4308-4a96-9069-3307d05b932e"}]->(b);
MATCH (a), (b) WHERE a.id = "ddafb0c6-9ba1-47ce-95de-415839048d98" AND b.id = "0f578787-4c5b-4c78-8bda-609ba3059940"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "competes_with", id: "fa8f49d6-5a24-47e1-9bfa-36c19ee34210"}]->(b);
MATCH (a), (b) WHERE a.id = "94be0006-7f27-4dc0-a405-f021aea002d1" AND b.id = "ddafb0c6-9ba1-47ce-95de-415839048d98"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "07f7fae0-bb83-4da3-89ad-62345b2fd236"}]->(b);
MATCH (a), (b) WHERE a.id = "94be0006-7f27-4dc0-a405-f021aea002d1" AND b.id = "d94060b2-b8ec-4fd1-a232-a8f5e4750fed"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "096f73b1-8a0b-4f8d-82d9-f4bf87da359e"}]->(b);
MATCH (a), (b) WHERE a.id = "94be0006-7f27-4dc0-a405-f021aea002d1" AND b.id = "01d34d74-2f74-4ba5-97b6-de52e54db6cf"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "8e7dd9df-582d-42e4-a7aa-59b75490fd5e"}]->(b);
MATCH (a), (b) WHERE a.id = "d860ddd7-8029-4567-8695-e54fb4d0537a" AND b.id = "ddafb0c6-9ba1-47ce-95de-415839048d98"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "221d9f55-8d16-4a87-8cee-556f7f783733"}]->(b);
MATCH (a), (b) WHERE a.id = "d860ddd7-8029-4567-8695-e54fb4d0537a" AND b.id = "d94060b2-b8ec-4fd1-a232-a8f5e4750fed"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "4fc59d3f-a546-4dae-bcda-f7955ca5ce67"}]->(b);
MATCH (a), (b) WHERE a.id = "d860ddd7-8029-4567-8695-e54fb4d0537a" AND b.id = "01d34d74-2f74-4ba5-97b6-de52e54db6cf"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "dc7007c2-26fa-4a5f-b8f9-d4df52b3c6a0"}]->(b);
MATCH (a), (b) WHERE a.id = "01d34d74-2f74-4ba5-97b6-de52e54db6cf" AND b.id = "0f578787-4c5b-4c78-8bda-609ba3059940"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "ce48698c-a4bf-4239-8276-d9f7ee2f5a89"}]->(b);
MATCH (a), (b) WHERE a.id = "0f578787-4c5b-4c78-8bda-609ba3059940" AND b.id = "9952a9f9-b4e8-4c57-bb9d-e94cad4ab14d"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "competes_with", id: "e76cbccd-5eec-4784-9fe7-04aa609a735a"}]->(b);
MATCH (a), (b) WHERE a.id = "26660adc-5464-4c1f-8552-4a14be154220" AND b.id = "0f578787-4c5b-4c78-8bda-609ba3059940"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "f1146ac9-5d56-4b99-a431-ba749bec73d6"}]->(b);
MATCH (a), (b) WHERE a.id = "26660adc-5464-4c1f-8552-4a14be154220" AND b.id = "d94060b2-b8ec-4fd1-a232-a8f5e4750fed"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "53810770-1ac9-45da-8bb1-de01575c63d2"}]->(b);
MATCH (a), (b) WHERE a.id = "26660adc-5464-4c1f-8552-4a14be154220" AND b.id = "01d34d74-2f74-4ba5-97b6-de52e54db6cf"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "5c36960d-6ff0-419d-8f31-5290b8ce63f3"}]->(b);
MATCH (a), (b) WHERE a.id = "50c1a29b-ede7-4dee-8bab-99556949ed97" AND b.id = "0f578787-4c5b-4c78-8bda-609ba3059940"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "8b41636f-ba66-4161-aeb5-8d4a28e7584e"}]->(b);
MATCH (a), (b) WHERE a.id = "50c1a29b-ede7-4dee-8bab-99556949ed97" AND b.id = "d94060b2-b8ec-4fd1-a232-a8f5e4750fed"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "e12faea6-e7bf-4cb1-9433-d3b0d98fb3e1"}]->(b);
MATCH (a), (b) WHERE a.id = "50c1a29b-ede7-4dee-8bab-99556949ed97" AND b.id = "01d34d74-2f74-4ba5-97b6-de52e54db6cf"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "b80adf96-bf09-44e4-8da5-00aeaed5fd94"}]->(b);
MATCH (a), (b) WHERE a.id = "d94060b2-b8ec-4fd1-a232-a8f5e4750fed" AND b.id = "7b9d3af7-f336-4464-8c22-e3a4899f3fb2"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "92387cc5-d615-4728-aa9e-abbc9bdf4f3c"}]->(b);
MATCH (a), (b) WHERE a.id = "7b9d3af7-f336-4464-8c22-e3a4899f3fb2" AND b.id = "40bbd1fb-d85d-4c69-b17a-f908938cd652"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "71d10812-71f7-411d-b492-94ddda45c315"}]->(b);
MATCH (a), (b) WHERE a.id = "1f50b3bd-283a-4216-866d-46d5e74d2933" AND b.id = "40bbd1fb-d85d-4c69-b17a-f908938cd652"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "183f69e2-0f4d-44d9-9b45-b9dadfb0a419"}]->(b);
MATCH (a), (b) WHERE a.id = "1f50b3bd-283a-4216-866d-46d5e74d2933" AND b.id = "d94060b2-b8ec-4fd1-a232-a8f5e4750fed"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "517f8a6f-7cb2-4c88-b935-ee8fceb365c7"}]->(b);
MATCH (a), (b) WHERE a.id = "1f50b3bd-283a-4216-866d-46d5e74d2933" AND b.id = "7b9d3af7-f336-4464-8c22-e3a4899f3fb2"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "edea7ed2-e3b9-456d-b1fa-1b60dfc8ac00"}]->(b);
MATCH (a), (b) WHERE a.id = "63025fdb-e9d1-4b66-9d59-f959cefeb788" AND b.id = "40bbd1fb-d85d-4c69-b17a-f908938cd652"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "1ed72695-ae57-4c4b-8a4a-f53e8e25cc84"}]->(b);
MATCH (a), (b) WHERE a.id = "63025fdb-e9d1-4b66-9d59-f959cefeb788" AND b.id = "d94060b2-b8ec-4fd1-a232-a8f5e4750fed"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "f90da9ac-5228-4864-8e63-fd706d7a46da"}]->(b);
MATCH (a), (b) WHERE a.id = "63025fdb-e9d1-4b66-9d59-f959cefeb788" AND b.id = "7b9d3af7-f336-4464-8c22-e3a4899f3fb2"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "24b28f16-652a-4850-b99a-e5d657680038"}]->(b);
MATCH (a), (b) WHERE a.id = "7b9d3af7-f336-4464-8c22-e3a4899f3fb2" AND b.id = "7ce79b68-4f24-48e5-8af6-cc0f00e1760e"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "91b0454d-04bb-4010-80e2-7a2dc2754df7"}]->(b);
MATCH (a), (b) WHERE a.id = "d50f4b84-82a6-4b64-b335-20d4558c034a" AND b.id = "7ce79b68-4f24-48e5-8af6-cc0f00e1760e"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "48cd65da-41c0-483f-b5d3-7db77838d976"}]->(b);
MATCH (a), (b) WHERE a.id = "d50f4b84-82a6-4b64-b335-20d4558c034a" AND b.id = "d94060b2-b8ec-4fd1-a232-a8f5e4750fed"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "5d545187-1d4a-43a0-ba82-1108a42ad888"}]->(b);
MATCH (a), (b) WHERE a.id = "d50f4b84-82a6-4b64-b335-20d4558c034a" AND b.id = "7b9d3af7-f336-4464-8c22-e3a4899f3fb2"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "75fb3e53-6b46-41e3-8a32-ea3f6425fc46"}]->(b);
MATCH (a), (b) WHERE a.id = "a8cb68eb-c5d7-47db-bc80-80671a3e8ba1" AND b.id = "7ce79b68-4f24-48e5-8af6-cc0f00e1760e"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "0ac2377e-550c-4e87-a31f-74365bf8a3ec"}]->(b);
MATCH (a), (b) WHERE a.id = "a8cb68eb-c5d7-47db-bc80-80671a3e8ba1" AND b.id = "d94060b2-b8ec-4fd1-a232-a8f5e4750fed"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "85e3ea64-eaa3-4872-8c9e-86fe99b83270"}]->(b);
MATCH (a), (b) WHERE a.id = "a8cb68eb-c5d7-47db-bc80-80671a3e8ba1" AND b.id = "7b9d3af7-f336-4464-8c22-e3a4899f3fb2"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "801b33bd-20da-4d4a-85eb-5b1a402b4cbc"}]->(b);
MATCH (a), (b) WHERE a.id = "30dccb5e-779b-4ed1-89b7-b2104c4f0cfb" AND b.id = "ee015145-df29-482b-9714-8da29c995e3f"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "d5de328c-3029-45b6-a96c-c39ac11d5643"}]->(b);
MATCH (a), (b) WHERE a.id = "ee015145-df29-482b-9714-8da29c995e3f" AND b.id = "4084a781-bf45-4dfa-b406-4a8a82122f33"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "cf0bc531-a323-4191-876f-3f8102a17e32"}]->(b);
MATCH (a), (b) WHERE a.id = "8d5de1b7-e6ab-43f9-8fc1-deaab75ad033" AND b.id = "4084a781-bf45-4dfa-b406-4a8a82122f33"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "274cb3ff-0989-4a54-818c-a704aa8cec98"}]->(b);
MATCH (a), (b) WHERE a.id = "8d5de1b7-e6ab-43f9-8fc1-deaab75ad033" AND b.id = "30dccb5e-779b-4ed1-89b7-b2104c4f0cfb"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "761c972a-055b-4419-b9df-80b2107fc742"}]->(b);
MATCH (a), (b) WHERE a.id = "8d5de1b7-e6ab-43f9-8fc1-deaab75ad033" AND b.id = "ee015145-df29-482b-9714-8da29c995e3f"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "567c6325-3bdc-40ae-9108-bfd867222cc4"}]->(b);
MATCH (a), (b) WHERE a.id = "8d5de1b7-e6ab-43f9-8fc1-deaab75ad033" AND b.id = "05fe86e5-25df-44fb-8c29-c02d4785617a"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "friend_of", id: "c4112186-e903-41a1-b816-e0550e8b168c"}]->(b);
MATCH (a), (b) WHERE a.id = "dce8b130-a524-4816-af8b-1434f95a9339" AND b.id = "4084a781-bf45-4dfa-b406-4a8a82122f33"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "84662af1-d049-4e69-863c-213ac0daffce"}]->(b);
MATCH (a), (b) WHERE a.id = "dce8b130-a524-4816-af8b-1434f95a9339" AND b.id = "30dccb5e-779b-4ed1-89b7-b2104c4f0cfb"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "4e1a5b9d-a4c6-43b9-9ab6-12ac363a56c7"}]->(b);
MATCH (a), (b) WHERE a.id = "dce8b130-a524-4816-af8b-1434f95a9339" AND b.id = "ee015145-df29-482b-9714-8da29c995e3f"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "0384cc70-9968-4c0f-b9d1-c8a35fe2b07f"}]->(b);
MATCH (a), (b) WHERE a.id = "ee015145-df29-482b-9714-8da29c995e3f" AND b.id = "dccf82c6-4bd4-44ae-9ab1-84cd33773d94"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "dbc08b61-b3be-420b-9e8e-46292f37211c"}]->(b);
MATCH (a), (b) WHERE a.id = "baf8c6ef-602f-4da6-bd18-3064c707c70f" AND b.id = "dccf82c6-4bd4-44ae-9ab1-84cd33773d94"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "7bc0333f-0d5f-4ebe-90b1-f7866b5a401f"}]->(b);
MATCH (a), (b) WHERE a.id = "baf8c6ef-602f-4da6-bd18-3064c707c70f" AND b.id = "30dccb5e-779b-4ed1-89b7-b2104c4f0cfb"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "83ed1214-9bce-4617-bd60-e733263870c0"}]->(b);
MATCH (a), (b) WHERE a.id = "baf8c6ef-602f-4da6-bd18-3064c707c70f" AND b.id = "ee015145-df29-482b-9714-8da29c995e3f"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "2657b26b-673d-4513-a600-0c62f9efc57e"}]->(b);
MATCH (a), (b) WHERE a.id = "e86332b6-d02f-44b2-93f7-e8e420880c51" AND b.id = "dccf82c6-4bd4-44ae-9ab1-84cd33773d94"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "21639b16-42f2-415f-8b85-fc005a19367f"}]->(b);
MATCH (a), (b) WHERE a.id = "e86332b6-d02f-44b2-93f7-e8e420880c51" AND b.id = "30dccb5e-779b-4ed1-89b7-b2104c4f0cfb"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "b7dd2ed5-796a-4039-888c-334280c2f87c"}]->(b);
MATCH (a), (b) WHERE a.id = "e86332b6-d02f-44b2-93f7-e8e420880c51" AND b.id = "ee015145-df29-482b-9714-8da29c995e3f"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "e3a0d292-3898-4149-897e-d1e6c2f5bd59"}]->(b);
MATCH (a), (b) WHERE a.id = "30dccb5e-779b-4ed1-89b7-b2104c4f0cfb" AND b.id = "16c8d090-e0a2-4519-9b19-21a150ec1975"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "572a43c4-de06-4c5f-a8c5-69eb6dea45f1"}]->(b);
MATCH (a), (b) WHERE a.id = "16c8d090-e0a2-4519-9b19-21a150ec1975" AND b.id = "9952a9f9-b4e8-4c57-bb9d-e94cad4ab14d"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "10cf6944-6000-424d-a8c5-81e1eee13082"}]->(b);
MATCH (a), (b) WHERE a.id = "03d9ab33-4354-4283-a2be-789655e56bff" AND b.id = "9952a9f9-b4e8-4c57-bb9d-e94cad4ab14d"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "2f4f744a-ff68-4c33-844f-08a36df2d463"}]->(b);
MATCH (a), (b) WHERE a.id = "03d9ab33-4354-4283-a2be-789655e56bff" AND b.id = "30dccb5e-779b-4ed1-89b7-b2104c4f0cfb"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "e23520b7-809c-4a11-9e0f-db83087307be"}]->(b);
MATCH (a), (b) WHERE a.id = "03d9ab33-4354-4283-a2be-789655e56bff" AND b.id = "0fc4c135-bfa2-4ac0-bd23-d4acaeedca53"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "16ca3045-710a-40f0-af01-660d1b44fa0f"}]->(b);
MATCH (a), (b) WHERE a.id = "d9166727-9317-426b-8509-fd9e01ee73e3" AND b.id = "9952a9f9-b4e8-4c57-bb9d-e94cad4ab14d"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "63f5aba3-d28c-4662-b6de-0097b027d8b9"}]->(b);
MATCH (a), (b) WHERE a.id = "d9166727-9317-426b-8509-fd9e01ee73e3" AND b.id = "30dccb5e-779b-4ed1-89b7-b2104c4f0cfb"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "a19a1c86-4495-480d-9e4f-aa73245214fb"}]->(b);
MATCH (a), (b) WHERE a.id = "d9166727-9317-426b-8509-fd9e01ee73e3" AND b.id = "16c8d090-e0a2-4519-9b19-21a150ec1975"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "aea7b467-e75e-41f1-bf41-91903347628c"}]->(b);
MATCH (a), (b) WHERE a.id = "16c8d090-e0a2-4519-9b19-21a150ec1975" AND b.id = "c039a7db-23e0-4976-a7ff-a744edd86850"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "contains", id: "da7148cc-37c0-4336-ac9e-e600730ce156"}]->(b);
MATCH (a), (b) WHERE a.id = "5fba5d35-c70c-4a95-9631-1f61fdd268cb" AND b.id = "c039a7db-23e0-4976-a7ff-a744edd86850"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "fed18df3-9fb0-4c74-a85c-0474605e216d"}]->(b);
MATCH (a), (b) WHERE a.id = "5fba5d35-c70c-4a95-9631-1f61fdd268cb" AND b.id = "30dccb5e-779b-4ed1-89b7-b2104c4f0cfb"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "3b3eb45e-329b-4f20-a3d2-ff85b2ef7a0c"}]->(b);
MATCH (a), (b) WHERE a.id = "5fba5d35-c70c-4a95-9631-1f61fdd268cb" AND b.id = "16c8d090-e0a2-4519-9b19-21a150ec1975"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "195e4877-730c-4aa0-8ffd-5f8786bb0908"}]->(b);
MATCH (a), (b) WHERE a.id = "0c12bc40-f3dd-4d47-a425-6b123ec07325" AND b.id = "c039a7db-23e0-4976-a7ff-a744edd86850"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "employee_of", id: "f0db3030-5732-444c-8653-301c4ccdc18d"}]->(b);
MATCH (a), (b) WHERE a.id = "0c12bc40-f3dd-4d47-a425-6b123ec07325" AND b.id = "30dccb5e-779b-4ed1-89b7-b2104c4f0cfb"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "citizen_of", id: "7740334b-1884-4abb-958b-02a298b85280"}]->(b);
MATCH (a), (b) WHERE a.id = "0c12bc40-f3dd-4d47-a425-6b123ec07325" AND b.id = "16c8d090-e0a2-4519-9b19-21a150ec1975"
CREATE (a)-[:RELATED_TO {weight: 0.0, label: "lives_in", id: "590a5f1d-e0c4-43b5-92fa-adf59fa7817e"}]->(b);
