package deposit.api;
import deposit.common.*;
import java.util.*;
import java.util.stream.Stream;
import org.neo4j.procedure.*;

/** Public catalog for single-attempt actions. Operational metadata is server managed.
 * Neo4j parses an annotated String default as literal text. The optional execution ID uses
 * Object for a real null default and is validated as a canonical UUIDv7 string when present.
 */
public final class DepositAPI extends BaseAPI {
 @Procedure(name="deposit.create.class",mode=Mode.WRITE)
 public Stream<PublicResults.NodeRow> p0(@Name("name") String a0,@Name("properties") Map<String,Object> a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.create.class",Arrays.asList(a0,a1),operationId).map(PublicResults::node); }
 @Procedure(name="deposit.set.class",mode=Mode.WRITE)
 public Stream<PublicResults.NodeRow> p1(@Name("class") Object a0,@Name("patch") Map<String,Object> a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.set.class",Arrays.asList(a0,a1),operationId).map(PublicResults::node); }
 @Procedure(name="deposit.delete.class",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p2(@Name("class") Object a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.delete.class",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.get.class",mode=Mode.READ)
 public Stream<PublicResults.NodeRow> p3(@Name("selection") Object a0,@Name("token") String token) { return invokePublic(token,"deposit.get.class",Arrays.asList(a0)).map(PublicResults::node); }
 @Procedure(name="deposit.get.classPage",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p4(@Name("selection") Object a0,@Name("page") Map<String,Object> a1,@Name("token") String token) { return invokePublic(token,"deposit.get.classPage",Arrays.asList(a0,a1)).map(PublicResults::page); }
 @Procedure(name="deposit.create.object",mode=Mode.WRITE)
 public Stream<PublicResults.NodeRow> p5(@Name("class") Object a0,@Name("properties") Map<String,Object> a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.create.object",Arrays.asList(a0,a1),operationId).map(PublicResults::node); }
 @Procedure(name="deposit.set.object",mode=Mode.WRITE)
 public Stream<PublicResults.NodeRow> p6(@Name("objectId") String a0,@Name("patch") Map<String,Object> a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.set.object",Arrays.asList(a0,a1),operationId).map(PublicResults::node); }
 @Procedure(name="deposit.delete.object",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p7(@Name("objectId") String a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.delete.object",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.get.object",mode=Mode.READ)
 public Stream<PublicResults.NodeRow> p8(@Name("selection") Object a0,@Name("token") String token) { return invokePublic(token,"deposit.get.object",Arrays.asList(a0)).map(PublicResults::node); }
 @Procedure(name="deposit.get.objectPage",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p9(@Name("selection") Object a0,@Name("page") Map<String,Object> a1,@Name("token") String token) { return invokePublic(token,"deposit.get.objectPage",Arrays.asList(a0,a1)).map(PublicResults::page); }
 @Procedure(name="deposit.create.descriptor",mode=Mode.WRITE)
 public Stream<PublicResults.NodeRow> p10(@Name("objectId") String a0,@Name("name") String a1,@Name("value") Object a2,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.create.descriptor",Arrays.asList(a0,a1,a2),operationId).map(PublicResults::node); }
 @Procedure(name="deposit.set.descriptor",mode=Mode.WRITE)
 public Stream<PublicResults.NodeRow> p11(@Name("objectId") String a0,@Name("name") String a1,@Name("value") Object a2,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.set.descriptor",Arrays.asList(a0,a1,a2),operationId).map(PublicResults::node); }
 @Procedure(name="deposit.delete.descriptor",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p12(@Name("descriptor") Object a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.delete.descriptor",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.get.descriptor",mode=Mode.READ)
 public Stream<PublicResults.NodeRow> p13(@Name("selection") Object a0,@Name("token") String token) { return invokePublic(token,"deposit.get.descriptor",Arrays.asList(a0)).map(PublicResults::node); }
 @Procedure(name="deposit.get.descriptorPage",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p14(@Name("selection") Object a0,@Name("page") Map<String,Object> a1,@Name("token") String token) { return invokePublic(token,"deposit.get.descriptorPage",Arrays.asList(a0,a1)).map(PublicResults::page); }
 @Procedure(name="deposit.create.classDescriptor",mode=Mode.WRITE)
 public Stream<PublicResults.NodeRow> p15(@Name("class") Object a0,@Name("name") String a1,@Name("type") String a2,@Name("properties") Map<String,Object> a3,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.create.classDescriptor",Arrays.asList(a0,a1,a2,a3),operationId).map(PublicResults::node); }
 @Procedure(name="deposit.set.classDescriptor",mode=Mode.WRITE)
 public Stream<PublicResults.NodeRow> p16(@Name("classDescriptor") Object a0,@Name("patch") Map<String,Object> a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.set.classDescriptor",Arrays.asList(a0,a1),operationId).map(PublicResults::node); }
 @Procedure(name="deposit.delete.classDescriptor",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p17(@Name("classDescriptor") Object a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.delete.classDescriptor",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.get.classDescriptor",mode=Mode.READ)
 public Stream<PublicResults.NodeRow> p18(@Name("selection") Object a0,@Name("token") String token) { return invokePublic(token,"deposit.get.classDescriptor",Arrays.asList(a0)).map(PublicResults::node); }
 @Procedure(name="deposit.get.classDescriptorPage",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p19(@Name("selection") Object a0,@Name("page") Map<String,Object> a1,@Name("token") String token) { return invokePublic(token,"deposit.get.classDescriptorPage",Arrays.asList(a0,a1)).map(PublicResults::page); }
 @Procedure(name="deposit.create.userTool",mode=Mode.WRITE)
 public Stream<PublicResults.NodeRow> p20(@Name("name") String a0,@Name("properties") Map<String,Object> a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.create.userTool",Arrays.asList(a0,a1),operationId).map(PublicResults::node); }
 @Procedure(name="deposit.set.userTool",mode=Mode.WRITE)
 public Stream<PublicResults.NodeRow> p21(@Name("userTool") Object a0,@Name("patch") Map<String,Object> a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.set.userTool",Arrays.asList(a0,a1),operationId).map(PublicResults::node); }
 @Procedure(name="deposit.delete.userTool",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p22(@Name("userTool") Object a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.delete.userTool",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.get.userTool",mode=Mode.READ)
 public Stream<PublicResults.NodeRow> p23(@Name("selection") Object a0,@Name("token") String token) { return invokePublic(token,"deposit.get.userTool",Arrays.asList(a0)).map(PublicResults::node); }
 @Procedure(name="deposit.get.userToolPage",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p24(@Name("selection") Object a0,@Name("page") Map<String,Object> a1,@Name("token") String token) { return invokePublic(token,"deposit.get.userToolPage",Arrays.asList(a0,a1)).map(PublicResults::page); }
 @Procedure(name="deposit.create.relationship",mode=Mode.WRITE)
 public Stream<PublicResults.RelRow> p25(@Name("sourceId") String a0,@Name("targetId") String a1,@Name("label") String a2,@Name("properties") Map<String,Object> a3,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.create.relationship",Arrays.asList(a0,a1,a2,a3),operationId).map(PublicResults::rel); }
 @Procedure(name="deposit.set.relationship",mode=Mode.WRITE)
 public Stream<PublicResults.RelRow> p26(@Name("relationshipId") String a0,@Name("patch") Map<String,Object> a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.set.relationship",Arrays.asList(a0,a1),operationId).map(PublicResults::rel); }
 @Procedure(name="deposit.delete.relationship",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p27(@Name("relationshipId") String a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.delete.relationship",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.get.relationship",mode=Mode.READ)
 public Stream<PublicResults.RelRow> p28(@Name("selection") Object a0,@Name("token") String token) { return invokePublic(token,"deposit.get.relationship",Arrays.asList(a0)).map(PublicResults::rel); }
 @Procedure(name="deposit.get.relationshipPage",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p29(@Name("selection") Object a0,@Name("page") Map<String,Object> a1,@Name("token") String token) { return invokePublic(token,"deposit.get.relationshipPage",Arrays.asList(a0,a1)).map(PublicResults::page); }
 @Procedure(name="deposit.create.classRelationship",mode=Mode.WRITE)
 public Stream<PublicResults.RelRow> p30(@Name("source") Object a0,@Name("target") Object a1,@Name("label") String a2,@Name("properties") Map<String,Object> a3,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.create.classRelationship",Arrays.asList(a0,a1,a2,a3),operationId).map(PublicResults::rel); }
 @Procedure(name="deposit.set.classRelationship",mode=Mode.WRITE)
 public Stream<PublicResults.RelRow> p31(@Name("classRelationshipId") String a0,@Name("patch") Map<String,Object> a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.set.classRelationship",Arrays.asList(a0,a1),operationId).map(PublicResults::rel); }
 @Procedure(name="deposit.delete.classRelationship",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p32(@Name("classRelationshipId") String a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.delete.classRelationship",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.get.classRelationship",mode=Mode.READ)
 public Stream<PublicResults.RelRow> p33(@Name("selection") Object a0,@Name("token") String token) { return invokePublic(token,"deposit.get.classRelationship",Arrays.asList(a0)).map(PublicResults::rel); }
 @Procedure(name="deposit.get.classRelationshipPage",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p34(@Name("selection") Object a0,@Name("page") Map<String,Object> a1,@Name("token") String token) { return invokePublic(token,"deposit.get.classRelationshipPage",Arrays.asList(a0,a1)).map(PublicResults::page); }
 @Procedure(name="deposit.set.membership",mode=Mode.WRITE)
 public Stream<PublicResults.RelRow> p35(@Name("objectId") String a0,@Name("class") Object a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.set.membership",Arrays.asList(a0,a1),operationId).map(PublicResults::rel); }
 @Procedure(name="deposit.apply.batch",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p36(@Name("commands") List<Object> a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.apply.batch",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.delete.all",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p37(@Name("confirmNamespaceName") String a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.delete.all",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Admin
 @Procedure(name="deposit.utils.bootstrap",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p38(@Name("username") String a0,@Name("password") String a1) { return invokePublic(null,"deposit.utils.bootstrap",Arrays.asList(a0,a1)).map(PublicResults::data); }
 @Procedure(name="deposit.utils.login",mode=Mode.READ)
 public Stream<PublicResults.ValueRow> p39(@Name("username") String a0,@Name("password") String a1) { return invokePublic(null,"deposit.utils.login",Arrays.asList(a0,a1)).map(PublicResults::value); }
 @Procedure(name="deposit.utils.logout",mode=Mode.READ)
 public Stream<PublicResults.ValueRow> p40(@Name("token") String token) { return invokePublic(token,"deposit.utils.logout",Arrays.asList()).map(PublicResults::value); }
 @Procedure(name="deposit.create.user",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p41(@Name("username") String a0,@Name("password") String a1,@Name("properties") Map<String,Object> a2,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.create.user",Arrays.asList(a0,a1,a2),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.create.namespace",mode=Mode.WRITE)
 public Stream<PublicResults.NodeRow> p42(@Name("name") String a0,@Name("properties") Map<String,Object> a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.create.namespace",Arrays.asList(a0,a1),operationId).map(PublicResults::node); }
 @Procedure(name="deposit.set.user",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p43(@Name("user") Object a0,@Name("patch") Map<String,Object> a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.set.user",Arrays.asList(a0,a1),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.set.password",mode=Mode.WRITE)
 public Stream<PublicResults.ValueRow> p44(@Name("oldPassword") String a0,@Name("newPassword") String a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.set.password",Arrays.asList(a0,a1),operationId).map(PublicResults::value); }
 @Procedure(name="deposit.set.namespace",mode=Mode.WRITE)
 public Stream<PublicResults.NodeRow> p45(@Name("patch") Map<String,Object> a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.set.namespace",Arrays.asList(a0),operationId).map(PublicResults::node); }
 @Procedure(name="deposit.delete.namespace",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p46(@Name("confirmNamespaceName") String a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.delete.namespace",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.set.namespaceRole",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p47(@Name("user") Object a0,@Name("role") String a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.set.namespaceRole",Arrays.asList(a0,a1),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.set.activeNamespace",mode=Mode.READ)
 public Stream<PublicResults.NodeRow> p48(@Name("namespace") Object a0,@Name("token") String token) { return invokePublic(token,"deposit.set.activeNamespace",Arrays.asList(a0)).map(PublicResults::node); }
 @Procedure(name="deposit.get.activeNamespace",mode=Mode.READ)
 public Stream<PublicResults.NodeRow> p49(@Name("token") String token) { return invokePublic(token,"deposit.get.activeNamespace",Arrays.asList()).map(PublicResults::node); }
 @Procedure(name="deposit.set.administrativeMode",mode=Mode.READ)
 public Stream<PublicResults.ValueRow> p50(@Name("enabled") Boolean a0,@Name("token") String token) { return invokePublic(token,"deposit.set.administrativeMode",Arrays.asList(a0)).map(PublicResults::value); }
 @Procedure(name="deposit.get.administrativeMode",mode=Mode.READ)
 public Stream<PublicResults.ValueRow> p51(@Name("token") String token) { return invokePublic(token,"deposit.get.administrativeMode",Arrays.asList()).map(PublicResults::value); }
 @Procedure(name="deposit.get.user",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p52(@Name("selection") Object a0,@Name("token") String token) { return invokePublic(token,"deposit.get.user",Arrays.asList(a0)).map(PublicResults::data); }
 @Procedure(name="deposit.get.userPage",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p53(@Name("selection") Object a0,@Name("page") Map<String,Object> a1,@Name("token") String token) { return invokePublic(token,"deposit.get.userPage",Arrays.asList(a0,a1)).map(PublicResults::page); }
 @Procedure(name="deposit.get.namespace",mode=Mode.READ)
 public Stream<PublicResults.NodeRow> p54(@Name("selection") Object a0,@Name("token") String token) { return invokePublic(token,"deposit.get.namespace",Arrays.asList(a0)).map(PublicResults::node); }
 @Procedure(name="deposit.get.namespacePage",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p55(@Name("selection") Object a0,@Name("page") Map<String,Object> a1,@Name("token") String token) { return invokePublic(token,"deposit.get.namespacePage",Arrays.asList(a0,a1)).map(PublicResults::page); }
 @Procedure(name="deposit.get.objectClass",mode=Mode.READ)
 public Stream<PublicResults.NodeRow> p56(@Name("objectId") String a0,@Name("token") String token) { return invokePublic(token,"deposit.get.objectClass",Arrays.asList(a0)).map(PublicResults::node); }
 @Procedure(name="deposit.get.classMembers",mode=Mode.READ)
 public Stream<PublicResults.NodeRow> p57(@Name("class") Object a0,@Name("token") String token) { return invokePublic(token,"deposit.get.classMembers",Arrays.asList(a0)).map(PublicResults::node); }
 @Procedure(name="deposit.get.classMembersPage",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p58(@Name("class") Object a0,@Name("page") Map<String,Object> a1,@Name("token") String token) { return invokePublic(token,"deposit.get.classMembersPage",Arrays.asList(a0,a1)).map(PublicResults::page); }
 @Procedure(name="deposit.get.objectByDescriptorId",mode=Mode.READ)
 public Stream<PublicResults.NodeRow> p59(@Name("descriptorId") String a0,@Name("token") String token) { return invokePublic(token,"deposit.get.objectByDescriptorId",Arrays.asList(a0)).map(PublicResults::node); }
 @Procedure(name="deposit.get.namespaceRoles",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p60(@Name("token") String token) { return invokePublic(token,"deposit.get.namespaceRoles",Arrays.asList()).map(PublicResults::data); }
 @Procedure(name="deposit.get.namespaceRolesPage",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p61(@Name("page") Map<String,Object> a0,@Name("token") String token) { return invokePublic(token,"deposit.get.namespaceRolesPage",Arrays.asList(a0)).map(PublicResults::page); }
 @Procedure(name="deposit.get.operation",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p62(@Name("operationId") String a0,@Name("scope") String a1,@Name("token") String token) { return invokePublic(token,"deposit.get.operation",Arrays.asList(a0,a1)).map(PublicResults::data); }
 @Procedure(name="deposit.get.revision",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p63(@Name("token") String token) { return invokePublic(token,"deposit.get.revision",Arrays.asList()).map(PublicResults::data); }
 @Procedure(name="deposit.get.history",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p64(@Name("token") String token) { return invokePublic(token,"deposit.get.history",Arrays.asList()).map(PublicResults::data); }
 @Procedure(name="deposit.get.historyPage",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p65(@Name("page") Map<String,Object> a0,@Name("token") String token) { return invokePublic(token,"deposit.get.historyPage",Arrays.asList(a0)).map(PublicResults::page); }
 @Procedure(name="deposit.get.restorePreview",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p66(@Name("targetRevisionId") String a0,@Name("token") String token) { return invokePublic(token,"deposit.get.restorePreview",Arrays.asList(a0)).map(PublicResults::data); }
 @Procedure(name="deposit.utils.undo",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p67(@Name("revisionId") String a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.utils.undo",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.utils.redo",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p68(@Name("undoRevisionId") String a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.utils.redo",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.utils.restore",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p69(@Name("targetRevisionId") String a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.utils.restore",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.utils.canUndo",mode=Mode.READ)
 public Stream<PublicResults.EligibilityRow> p70(@Name("revisionId") String a0,@Name("token") String token) { return invokePublic(token,"deposit.utils.canUndo",Arrays.asList(a0)).map(PublicResults::eligibility); }
 @Procedure(name="deposit.utils.isRestored",mode=Mode.READ)
 public Stream<PublicResults.ValueRow> p71(@Name("token") String token) { return invokePublic(token,"deposit.utils.isRestored",Arrays.asList()).map(PublicResults::value); }
 @Procedure(name="deposit.set.versioningType",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p72(@Name("mode") String a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.set.versioningType",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.set.versionTag",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p73(@Name("revisionId") String a0,@Name("tag") String a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.set.versionTag",Arrays.asList(a0,a1),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.get.query",mode=Mode.READ)
 public Stream<PublicResults.QueryRow> p74(@Name("query") String a0,@Name("parameters") Map<String,Object> a1,@Name("options") Map<String,Object> a2,@Name("token") String token) { return invokePublic(token,"deposit.get.query",Arrays.asList(a0,a1,a2)).map(PublicResults::query); }
 @Procedure(name="deposit.get.queryPage",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p75(@Name("query") String a0,@Name("parameters") Map<String,Object> a1,@Name("options") Map<String,Object> a2,@Name("token") String token) { return invokePublic(token,"deposit.get.queryPage",Arrays.asList(a0,a1,a2)).map(PublicResults::page); }
 @Procedure(name="deposit.resource.beginUpload",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p76(@Name("filename") String a0,@Name("bytes") Long a1,@Name("sha256") String a2,@Name("mediaType") String a3,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.resource.beginUpload",Arrays.asList(a0,a1,a2,a3),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.resource.uploadChunk",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p77(@Name("uploadId") String a0,@Name("offset") Long a1,@Name("bytesBase64") String a2,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.resource.uploadChunk",Arrays.asList(a0,a1,a2),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.resource.finishUpload",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p78(@Name("uploadId") String a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.resource.finishUpload",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.resource.abortUpload",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p79(@Name("uploadId") String a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.resource.abortUpload",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.resource.info",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p80(@Name("resourceId") String a0,@Name("token") String token) { return invokePublic(token,"deposit.resource.info",Arrays.asList(a0)).map(PublicResults::data); }
 @Procedure(name="deposit.resource.downloadChunk",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p81(@Name("resourceId") String a0,@Name("offset") Long a1,@Name("length") Long a2,@Name("token") String token) { return invokePublic(token,"deposit.resource.downloadChunk",Arrays.asList(a0,a1,a2)).map(PublicResults::data); }
 @Procedure(name="deposit.admin.pruneResources",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p82(@Name("options") Map<String,Object> a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.admin.pruneResources",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.admin.pruneResourcesStatus",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p83(@Name("pruneId") String a0,@Name("token") String token) { return invokePublic(token,"deposit.admin.pruneResourcesStatus",Arrays.asList(a0)).map(PublicResults::data); }
 @Procedure(name="deposit.utils.coordinatesToWKT",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p84(@Name("type") String a0,@Name("coordinates") Object a1,@Name("options") Map<String,Object> a2,@Name("token") String token) { return invokePublic(token,"deposit.utils.coordinatesToWKT",Arrays.asList(a0,a1,a2)).map(PublicResults::data); }
 @Procedure(name="deposit.utils.wktToCoordinates",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p85(@Name("wkt") String a0,@Name("options") Map<String,Object> a1,@Name("token") String token) { return invokePublic(token,"deposit.utils.wktToCoordinates",Arrays.asList(a0,a1)).map(PublicResults::data); }
 @Procedure(name="deposit.utils.toData",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p86(@Name("entity") Object a0,@Name("token") String token) { return invokePublic(token,"deposit.utils.toData",Arrays.asList(a0)).map(PublicResults::data); }
 @Procedure(name="deposit.get.session",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p87(@Name("token") String token) { return invokePublic(token,"deposit.get.session",Arrays.asList()).map(PublicResults::data); }
 @Procedure(name="deposit.action.begin",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> beginOperation(@Name("operationId") String operationId,@Name("scope") String scope,@Name("procedure") String procedure,@Name("arguments") List<Object> arguments,@Name("token") String token) {
     return invokePublic(token,"deposit.action.begin",Arrays.asList(operationId,scope,procedure,arguments)).map(PublicResults::data);
 }
 @Procedure(name="deposit.action.cancel",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> cancelOperation(@Name("operationId") String operationId,@Name("scope") String scope,@Name("token") String token) {
     return invokePublic(token,"deposit.action.cancel",Arrays.asList(operationId,scope)).map(PublicResults::data);
 }
 @Procedure(name="deposit.utils.refresh",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p88(@Name("scope") String a0,@Name("token") String token) { return invokePublic(token,"deposit.utils.refresh",Arrays.asList(a0)).map(PublicResults::data); }
 @Procedure(name="deposit.get.lastOperation",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p89(@Name("token") String token) { return invokePublic(token,"deposit.get.lastOperation",Arrays.asList()).map(PublicResults::data); }
 @Procedure(name="deposit.get.requests",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p90(@Name("token") String token) { return invokePublic(token,"deposit.get.requests",Arrays.asList()).map(PublicResults::data); }
 @Procedure(name="deposit.utils.cancel",mode=Mode.READ)
 public Stream<PublicResults.ValueRow> p91(@Name("token") String token) { return invokePublic(token,"deposit.utils.cancel",Arrays.asList()).map(PublicResults::value); }
 @Procedure(name="deposit.utils.cancelRequest",mode=Mode.READ)
 public Stream<PublicResults.ValueRow> p92(@Name("requestId") String a0,@Name("token") String token) { return invokePublic(token,"deposit.utils.cancelRequest",Arrays.asList(a0)).map(PublicResults::value); }
 @Procedure(name="deposit.create.pattern",mode=Mode.WRITE)
 public Stream<PublicResults.NodeRow> p97(@Name("name") String a0,@Name("definition") Map<String,Object> a1,@Name("properties") Map<String,Object> a2,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.create.pattern",Arrays.asList(a0,a1,a2),operationId).map(PublicResults::node); }
 @Procedure(name="deposit.set.pattern",mode=Mode.WRITE)
 public Stream<PublicResults.NodeRow> p98(@Name("pattern") Object a0,@Name("patch") Map<String,Object> a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.set.pattern",Arrays.asList(a0,a1),operationId).map(PublicResults::node); }
 @Procedure(name="deposit.delete.pattern",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p99(@Name("pattern") Object a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.delete.pattern",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.get.pattern",mode=Mode.READ)
 public Stream<PublicResults.NodeRow> p100(@Name("selection") Object a0,@Name("token") String token) { return invokePublic(token,"deposit.get.pattern",Arrays.asList(a0)).map(PublicResults::node); }
 @Procedure(name="deposit.get.patternPage",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p101(@Name("selection") Object a0,@Name("page") Map<String,Object> a1,@Name("token") String token) { return invokePublic(token,"deposit.get.patternPage",Arrays.asList(a0,a1)).map(PublicResults::page); }
 @Procedure(name="deposit.get.objectsByPattern",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p102(@Name("pattern") Object a0,@Name("values") Map<String,Object> a1,@Name("rules") Map<String,Object> a2,@Name("token") String token) { return invokePublic(token,"deposit.get.objectsByPattern",Arrays.asList(a0,a1,a2)).map(PublicResults::data); }
 @Procedure(name="deposit.get.objectsByPatternPage",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p103(@Name("pattern") Object a0,@Name("values") Map<String,Object> a1,@Name("rules") Map<String,Object> a2,@Name("page") Map<String,Object> a3,@Name("token") String token) { return invokePublic(token,"deposit.get.objectsByPatternPage",Arrays.asList(a0,a1,a2,a3)).map(PublicResults::page); }
 @Procedure(name="deposit.get.data",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p104(@Name("rootIds") List<String> a0,@Name("options") Map<String,Object> a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.get.data",Arrays.asList(a0,a1),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.get.namespaceData",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p105(@Name("options") Map<String,Object> a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.get.namespaceData",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.import.analyze",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p108(@Name("source") Map<String,Object> a0,@Name("rules") Map<String,Object> a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.import.analyze",Arrays.asList(a0,a1),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.get.importPlan",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p109(@Name("planId") String a0,@Name("token") String token) { return invokePublic(token,"deposit.get.importPlan",Arrays.asList(a0)).map(PublicResults::data); }
 @Procedure(name="deposit.get.importPlanMetadata",mode=Mode.READ)
 public Stream<PublicResults.DataRow> importPlanMetadata(@Name("planId") String planId,@Name("token") String token) { return invokePublic(token,"deposit.get.importPlanMetadata",Arrays.asList(planId)).map(PublicResults::data); }
 @Procedure(name="deposit.get.importResult",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p123(@Name("resultId") String a0,@Name("token") String token) { return invokePublic(token,"deposit.get.importResult",Arrays.asList(a0)).map(PublicResults::data); }
 @Procedure(name="deposit.get.importResultPage",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p124(@Name("resultId") String a0,@Name("leaseId") String leaseId,@Name("collection") String a1,@Name("cursor") Map<String,Object> a2,@Name("limit") Long a3,@Name("token") String token) { return invokePublic(token,"deposit.get.importResultPage",Arrays.asList(a0,leaseId,a1,a2,a3)).map(PublicResults::data); }
 @Procedure(name="deposit.get.importResultEntry",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p125(@Name("resultId") String a0,@Name("leaseId") String leaseId,@Name("collection") String a1,@Name("key") String a2,@Name("token") String token) { return invokePublic(token,"deposit.get.importResultEntry",Arrays.asList(a0,leaseId,a1,a2)).map(PublicResults::data); }
 @Procedure(name="deposit.import.discardPlan",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> discardImportPlan(@Name("planId") String planId,@Name("token") String token) { return invokePublic(token,"deposit.import.discardPlan",List.of(planId)).map(PublicResults::data); }
 @Procedure(name="deposit.import.apply",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p110(@Name("planId") String a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.import.apply",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.import.cloneNamespace",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p111(@Name("source") Map<String,Object> a0,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.import.cloneNamespace",Arrays.asList(a0),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.load.csv",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p112(@Name("resourceId") String a0,@Name("formatOptions") Map<String,Object> a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.load.csv",Arrays.asList(a0,a1),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.load.xlsx",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p113(@Name("resourceId") String a0,@Name("formatOptions") Map<String,Object> a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.load.xlsx",Arrays.asList(a0,a1),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.load.gpkg",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p114(@Name("resourceId") String a0,@Name("formatOptions") Map<String,Object> a1,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.load.gpkg",Arrays.asList(a0,a1),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.get.table",mode=Mode.WRITE)
 public Stream<PublicResults.QueryRow> p115(@Name("tableId") String a0,@Name("token") String token) { return invokePublic(token,"deposit.get.table",Arrays.asList(a0)).map(PublicResults::query); }
 @Procedure(name="deposit.get.tablePage",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p116(@Name("tableId") String a0,@Name("page") Map<String,Object> a1,@Name("token") String token) { return invokePublic(token,"deposit.get.tablePage",Arrays.asList(a0,a1)).map(PublicResults::page); }
 @Procedure(name="deposit.save.csv",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p117(@Name("source") Map<String,Object> a0,@Name("parameters") Map<String,Object> a1,@Name("formatOptions") Map<String,Object> a2,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.save.csv",Arrays.asList(a0,a1,a2),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.save.xlsx",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p118(@Name("source") Map<String,Object> a0,@Name("parameters") Map<String,Object> a1,@Name("formatOptions") Map<String,Object> a2,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.save.xlsx",Arrays.asList(a0,a1,a2),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.save.gpkg",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p119(@Name("source") Map<String,Object> a0,@Name("parameters") Map<String,Object> a1,@Name("formatOptions") Map<String,Object> a2,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.save.gpkg",Arrays.asList(a0,a1,a2),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.get.layout",mode=Mode.READ)
 public Stream<PublicResults.DataRow> p120(@Name("selection") Map<String,Object> a0,@Name("options") Map<String,Object> a1,@Name("token") String token) { return invokePublic(token,"deposit.get.layout",Arrays.asList(a0,a1)).map(PublicResults::data); }
 @Procedure(name="deposit.import.rows",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p121(@Name("source") Map<String,Object> a0,@Name("targets") Map<String,Object> a1,@Name("relationships") List<Object> a2,@Name("rules") Map<String,Object> a3,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.import.rows",Arrays.asList(a0,a1,a2,a3),operationId).map(PublicResults::data); }
 @Procedure(name="deposit.import.dataByPattern",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> p122(@Name("pattern") Object a0,@Name("values") Map<String,Object> a1,@Name("rules") Map<String,Object> a2,@Name("token") String token,@Name(value="operationId", defaultValue="null") Object operationId) { return invokePublic(token,"deposit.import.dataByPattern",Arrays.asList(a0,a1,a2),operationId).map(PublicResults::data); }

 @Procedure(name="deposit.exchange.export",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> exchangeExport(@Name("rootIds") List<String> rootIds, @Name("options") Map<String,Object> options, @Name("token") String token, @Name(value="operationId", defaultValue="null") Object operationId) {
  return invokePublic(token,"deposit.exchange.export",Arrays.asList(rootIds, options),operationId).map(PublicResults::data);
 }
 @Procedure(name="deposit.exchange.beginUpload",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> exchangeBeginupload(@Name("scope") String scope, @Name("inputKind") String inputKind, @Name("metadata") Map<String,Object> metadata, @Name("token") String token, @Name(value="operationId", defaultValue="null") Object operationId) {
  return invokePublic(token,"deposit.exchange.beginUpload",Arrays.asList(scope, inputKind, metadata),operationId).map(PublicResults::data);
 }
 @Procedure(name="deposit.exchange.uploadChunk",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> exchangeUploadchunk(@Name("uploadId") String uploadId, @Name("member") String member, @Name("offset") Long offset, @Name("bytesBase64") String bytesBase64, @Name("token") String token, @Name(value="operationId", defaultValue="null") Object operationId) {
  return invokePublic(token,"deposit.exchange.uploadChunk",Arrays.asList(uploadId, member, offset, bytesBase64),operationId).map(PublicResults::data);
 }
 @Procedure(name="deposit.exchange.finishUpload",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> exchangeFinishupload(@Name("uploadId") String uploadId, @Name("token") String token, @Name(value="operationId", defaultValue="null") Object operationId) {
  return invokePublic(token,"deposit.exchange.finishUpload",Arrays.asList(uploadId),operationId).map(PublicResults::data);
 }
 @Procedure(name="deposit.exchange.abortUpload",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> exchangeAbortupload(@Name("uploadId") String uploadId, @Name("token") String token, @Name(value="operationId", defaultValue="null") Object operationId) {
  return invokePublic(token,"deposit.exchange.abortUpload",Arrays.asList(uploadId),operationId).map(PublicResults::data);
 }
 @Procedure(name="deposit.exchange.inspect",mode=Mode.READ)
 public Stream<PublicResults.DataRow> exchangeInspect(@Name("packageId") String packageId, @Name("grant") String grant, @Name("token") String token) {
  return invokePublic(token,"deposit.exchange.inspect",Arrays.asList(packageId, grant),null).map(PublicResults::data);
 }
 @Procedure(name="deposit.exchange.memberPage",mode=Mode.READ)
 public Stream<PublicResults.DataRow> exchangeMemberpage(@Name("packageId") String packageId, @Name("grant") String grant, @Name("leaseId") String leaseId, @Name("cursor") Map<String,Object> cursor, @Name("limit") Long limit, @Name("token") String token) {
  return invokePublic(token,"deposit.exchange.memberPage",Arrays.asList(packageId, grant, leaseId, cursor, limit),null).map(PublicResults::data);
 }
 @Procedure(name="deposit.exchange.graphPage",mode=Mode.READ)
 public Stream<PublicResults.DataRow> exchangeGraphpage(@Name("packageId") String packageId, @Name("grant") String grant, @Name("leaseId") String leaseId, @Name("collection") String collection, @Name("cursor") Map<String,Object> cursor, @Name("limit") Long limit, @Name("token") String token) {
  return invokePublic(token,"deposit.exchange.graphPage",Arrays.asList(packageId, grant, leaseId, collection, cursor, limit),null).map(PublicResults::data);
 }
 @Procedure(name="deposit.exchange.readChunk",mode=Mode.READ)
 public Stream<PublicResults.DataRow> exchangeReadchunk(@Name("packageId") String packageId, @Name("grant") String grant, @Name("leaseId") String leaseId, @Name("member") String member, @Name("offset") Long offset, @Name("length") Long length, @Name("token") String token) {
  return invokePublic(token,"deposit.exchange.readChunk",Arrays.asList(packageId, grant, leaseId, member, offset, length),null).map(PublicResults::data);
 }
 @Procedure(name="deposit.exchange.discard",mode=Mode.WRITE)
 public Stream<PublicResults.DataRow> exchangeDiscard(@Name("packageId") String packageId, @Name("grant") String grant, @Name("token") String token) {
  return invokePublic(token,"deposit.exchange.discard",Arrays.asList(packageId, grant),null).map(PublicResults::data);
 }
}
