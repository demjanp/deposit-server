package deposit.repository;

import deposit.common.FileSystem;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

public class UtilsUploadRepository extends BaseRepository {

	private static UtilsUploadRepository instance;
	
	private UtilsUploadRepository() {}
	
	public static synchronized UtilsUploadRepository getInstance() {
		if (instance == null) {
			instance = new UtilsUploadRepository();
		}
		return instance;
	}
	
    public Node utilsResource(Transaction tx, String stream, String filename) {
        String storedFilename = FileSystem.storeFile(stream, filename);
        boolean isImage = false; // TODO: implement checking if resource is an image
        Node node = tx.createNode(Label.label("DResource"));
        node.setProperty("filename", filename);
        node.setProperty("storedFilename", storedFilename);
        node.setProperty("isImage", isImage);
        return node;
    }
}
