package deposit.repository;

import deposit.common.*;
import java.util.*;
import org.neo4j.graphdb.*;

/** Durable preparation has no domain effects and never stores argument payloads. */
public final class PreparedActionRepository {
    private final Transaction tx;
    public PreparedActionRepository(Transaction tx){this.tx=tx;}
    public Node find(String actor,String key){try(var nodes=tx.findNodes(Label.label("DepositPreparedAction"),Map.of("actorId",actor,"recoveryKey",key))){return nodes.hasNext()?nodes.next():null;}}
    public void lock(Node action){tx.acquireWriteLock(action);}
    public Node create(String actor,String key,Map<String,Object> properties){
        Node n=tx.createNode(Label.label("DepositPreparedAction"));n.setProperty("actorId",actor);n.setProperty("recoveryKey",key);
        properties.forEach(n::setProperty);return n;
    }
    public Map<String,Object> state(Node action,CoreRepository core){
        String scope=(String)action.getProperty("scope"),operation=(String)action.getProperty("operationId");Node receipt=core.receipt(scope,operation);
        return Json.map("recoveryKey",action.getProperty("recoveryKey"),"operationId",operation,"procedure",action.getProperty("procedure"),"scope",scope,
            "state",receipt==null?"prepared":"committed","outcome",receipt==null?null:Json.read((String)receipt.getProperty("outcome")));
    }
}
