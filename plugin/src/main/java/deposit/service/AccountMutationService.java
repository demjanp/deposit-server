package deposit.service;

import deposit.common.*;
import deposit.common.Results.Outcome;
import deposit.common.Results.ValueRow;
import deposit.repository.CoreRepository;
import java.util.*;
import org.neo4j.graphdb.Node;

/** Server-account mutations and their authorization/revocation rules. */
final class AccountMutationService extends ActionService {
    AccountMutationService(RequestContext c) { super(c); }
    Outcome createUser(Map<String,Object> request) {
        fields(request,"username","password","serverRole");admin(authenticate());
        return action("deposit.create.user",request,true,null,()->{
            String username=name(request.get("username")),role=string(request.getOrDefault("serverRole","user"));
            if(!Set.of("user","administrator").contains(role))c.fail("VALIDATION_FAILED","Invalid serverRole");
            if(repo.find("User","username",username)!=null)c.fail("DUPLICATE","Username exists");
            Node n=repo.create("User",null,c.actorId,Json.map("username",username,"passwordHash",AccountCredentials.hash(AccountCredentials.password(request.get("password"))),"enabled",true,"serverRole",role));
            return new Change(outcome(n),true);
        });
    }
    Outcome setUser(Map<String,Object> request) {
        fields(request,"userId","enabled","serverRole","newPassword");authorizationChange();admin(authenticate());
        return action("deposit.set.user",request,true,null,()->{
            Node target=required("User",request.get("userId"));
            var changes=new LinkedHashMap<String,Object>();
            if(request.containsKey("enabled")) {
                if(!(request.get("enabled") instanceof Boolean))c.fail("VALIDATION_FAILED","enabled must be Boolean");
                changes.put("enabled",request.get("enabled"));
            }
            if(request.containsKey("serverRole")) {
                String role=string(request.get("serverRole"));
                if(!Set.of("user","administrator").contains(role))c.fail("VALIDATION_FAILED","Invalid serverRole");
                changes.put("serverRole",role);
            }
            boolean enabled=(Boolean)changes.getOrDefault("enabled",target.getProperty("enabled"));
            String role=(String)changes.getOrDefault("serverRole",target.getProperty("serverRole"));
            if((!enabled||!role.equals("administrator"))&&Boolean.TRUE.equals(target.getProperty("enabled"))&&AuthorizationAdapter.administrator(target)) {
                boolean other=repo.nodes("User",Map.of("enabled",true,"serverRole","administrator")).stream().anyMatch(u->!u.equals(target));
                if(!other)c.fail("VALIDATION_FAILED","Server requires an enabled administrator");
            }
            if(!enabled)repo.validateOwnerDeactivation(CoreRepository.id(target));
            boolean passwordChanged=false;
            if(request.containsKey("newPassword")) {
                String value=AccountCredentials.password(request.get("newPassword"));
                passwordChanged=!AccountCredentials.matches(target,value);
                if(passwordChanged)changes.put("passwordHash",AccountCredentials.hash(value));
            }
            boolean disabled=Boolean.TRUE.equals(target.getProperty("enabled"))&&!enabled;
            boolean demoted=AuthorizationAdapter.administrator(target)&&!role.equals("administrator");
            boolean changed=repo.update(target,changes,c.actorId);String targetId=CoreRepository.id(target);
            if(changed) {
                c.state.invalidateAuthorization();
                c.clearResolved();
                if(targetId.equals(c.actorId))c.state.selfAccountChange=true;
            }
            if(passwordChanged||disabled)c.state.afterCommit.add(()->c.server.revokeUser(targetId));
            if(demoted)c.state.afterCommit.add(()->c.server.demoteUser(targetId));
            return new Change(outcome(target),changed);
        });
    }
    ValueRow setPassword(Map<String,Object> request) {
        fields(request,"oldPassword","newPassword");authorizationChange();
        Outcome result=action("deposit.set.password",request,true,null,()->{
            Node user=authenticate();
            if(!AccountCredentials.matches(user,string(request.get("oldPassword"))))c.fail("AUTH_FAILED","Invalid credentials");
            String value=AccountCredentials.password(request.get("newPassword"));boolean changed=!AccountCredentials.matches(user,value);
            if(changed) {
                repo.update(user,Map.of("passwordHash",AccountCredentials.hash(value)),c.actorId);
                c.state.invalidateAuthorization();
                c.state.afterCommit.add(()->c.server.revokeUser(c.actorId));
            }
            return new Change(new Outcome(null,null,Json.map("changed",changed)),changed);
        });
        return new ValueRow(result.data().get("changed"),c.meta());
    }
}
