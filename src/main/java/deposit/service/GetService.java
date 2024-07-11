package deposit.service;

import deposit.repository.GetRepository;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.logging.Log;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

public class GetService {

	private GetRepository getRepository;

	public GetService(GraphDatabaseService db, Log log) {
		this.getRepository = new GetRepository(db, log);
	}

    // Package-private setter for GetRepository to be used in tests
    void setGetRepository(GetRepository getRepository) {
        this.getRepository = getRepository;
    }
	
	public List<String> getDatabaseNames() {
		// TODO: Implement

        return List.of();
    }
	
	public String getVersion() {
		// TODO: Implement

        return "";
    }
	
	public String getVersioningType() {
		// TODO: Implement

        return "";
    }
	
	public String getCreatedBy() {
		// TODO: Implement

        return "";
    }
	
	public ZonedDateTime getCreatedAt() {
		// TODO: Implement

        return null;
    }
	
	public ZonedDateTime getUpdatedAt() {
		// TODO: Implement

        return null;
    }
	
	public String getDatabaseDescription() {
		// TODO: Implement

        return "";
    }
	
	public List<Node> getClass(String name) {
		// TODO: Implement
		return getRepository.getClass(name);
	}
	
	public List<Node> getObjectClasses(Node objectNode) {
		// TODO: Implement
		return getRepository.getObjectClasses(objectNode);
	}
	
	public Map<String, Map<String, Object>> getSubTree(List<Node> roots) {
		// TODO: Implement
		return getRepository.getSubTree(roots);
	}
	
	public List<Node> getObject(String uuid, List<String> classes, List<Object[]> descriptors, Map<String, Map<String, Object>> subtree) {
		// TODO: Implement
		return getRepository.getObject(uuid, classes, descriptors, subtree);
	}
	
	public List<Object[]> getDescriptor(Node objectNode, String name) {
		// TODO: Implement
		return getRepository.getDescriptor(objectNode, name);
	}
	
	public List<String[]> getClassDescriptor(Node classNode, String name) {
		// TODO: Implement
		return getRepository.getClassDescriptor(classNode, name);
	}
	
	public List<Double> getPosition(Node node) {
		// TODO: Implement
		return getRepository.getPosition(node);
	}
	
	public List<Object[]> getRelationship(Node srcObjectNode, Node tgtObjectNode) {
		// TODO: Implement
		return getRepository.getRelationship(srcObjectNode, tgtObjectNode);
	}
	
	public List<Object[]> getNeighbors(Node objectNode) {
		// TODO: Implement
		return getRepository.getNeighbors(objectNode);
	}
	
	public List<String[]> getClassRelationship(Node srcClassNode, Node tgtClassNode, String label) {
		// TODO: Implement
		return getRepository.getClassRelationship(srcClassNode, tgtClassNode, label);
	}
	
	public List<Object[]> getClassNeighbors(Node classNode) {
		// TODO: Implement
		return getRepository.getClassNeighbors(classNode);
	}
	
	public Map<String, List<Double>> getLayout(List<Node> elements) {
		// TODO: Implement
		return getRepository.getLayout(elements);
	}
	
	public List<Object[]> getUserTool(String name) {
		// TODO: Implement
		return getRepository.getUserTool(name);
	}
}
