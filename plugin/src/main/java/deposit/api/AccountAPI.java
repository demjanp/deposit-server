package deposit.api;

import deposit.common.Results.*;
import deposit.service.*;
import java.util.*;
import java.util.stream.Stream;
import org.neo4j.procedure.*;

public class AccountAPI extends BaseAPI {
    @Procedure(name="deposit.utils.bootstrap",mode=Mode.WRITE)
    public Stream<DataRow> bootstrap(@Name("request") Map<String,Object> request) {return invoke(Map.of(),c->Stream.of(new AccountService(c).bootstrap(request)));}
    @Procedure(name="deposit.utils.login",mode=Mode.READ)
    public Stream<LoginRow> login(@Name("request") Map<String,Object> request) {return invoke(Map.of(),c->Stream.of(new AccountService(c).login(request)));}
    @Procedure(name="deposit.utils.logout",mode=Mode.READ)
    public Stream<ValueRow> logout(@Name("request") Map<String,Object> request,@Name("context") Map<String,Object> context) {return invoke(context,c->Stream.of(new AccountService(c).logout(request)));}
    @Procedure(name="deposit.create.user",mode=Mode.WRITE)
    public Stream<DataRow> createUser(@Name("request") Map<String,Object> request,@Name("context") Map<String,Object> context) {return invoke(context,c->{return Stream.of(new AccountService(c).createUser(request).structured(c));});}
    @Procedure(name="deposit.create.namespace",mode=Mode.WRITE)
    public Stream<EntityRow> createNamespace(@Name("request") Map<String,Object> request,@Name("context") Map<String,Object> context) {return invoke(context,c->{return Stream.of(new AccountService(c).createNamespace(request).entity(c));});}
    @Procedure(name="deposit.set.activeNamespace",mode=Mode.READ)
    public Stream<EntityRow> setActiveNamespace(@Name("request") Map<String,Object> request,@Name("context") Map<String,Object> context) {return invoke(context,c->Stream.of(new AccountService(c).active(request,true)));}
    @Procedure(name="deposit.get.activeNamespace",mode=Mode.READ)
    public Stream<EntityRow> getActiveNamespace(@Name("request") Map<String,Object> request,@Name("context") Map<String,Object> context) {return invoke(context,c->Stream.of(new AccountService(c).active(request,false)));}
    @Procedure(name="deposit.set.namespaceRole",mode=Mode.WRITE)
    public Stream<DataRow> setRole(@Name("request") Map<String,Object> request,@Name("context") Map<String,Object> context) {return invoke(context,c->{return Stream.of(new AccountService(c).setRole(request).structured(c));});}
    @Procedure(name="deposit.get.operation",mode=Mode.READ)
    public Stream<DataRow> operation(@Name("request") Map<String,Object> request,@Name("context") Map<String,Object> context) {return invoke(context,c->Stream.of(new AccountService(c).operation(request)));}
}
