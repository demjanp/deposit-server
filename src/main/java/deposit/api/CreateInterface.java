package deposit.api;

import deposit.service.CreateService;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.logging.Log;
import org.neo4j.procedure.*;

import java.util.List;
import java.util.stream.Stream;

public class CreateInterface {
	
	@Context
	public GraphDatabaseService db;
	
	@Context
	public Log log;
	
	private CreateService createService;
	
	public CreateInterface(GraphDatabaseService db, Log log) {
		this.createService = new CreateService(db, log);
	}

	public CreateService getCreateService() {
        return createService;
    }

	public void setCreateService(CreateService createService) {
	   		this.createService = createService;
	}


	/**
	 * Creates a new database and adds a Metadata node with specified properties.
	 *
	 * @param databaseName the name of the database to create.
	 * @throws RuntimeException if the database name ends with '_archive' or if there is an error during creation.
	 */
	@Procedure(name = "deposit.create.database", mode = Mode.WRITE)
	@Description("CALL deposit.create.database(databaseName)")
	public void createDatabase(
			@Name("databaseName") String databaseName) {
	   createService.createDatabase(databaseName);
	}

	/**
	 * Creates a Class node with specified properties.
	 *
	 * @param name		  the name of the class.
	 * @param order		  the order of the class, default is 0.
	 * @param position	  the position of the class as a list of two floats [x, y], default is [0.0, 0.0].
	 * @param comment	  the comment for the class, default is an empty string.
	 * @param descriptors the descriptors for the class in the format [['<name>', '<type>'] or ['<name>', '<type>', '<comment>'], ...], default is an empty list.
	 * @return the created Class node.
	 * @throws RuntimeException if there is an error during creation or if position is invalid.
	 */
	@Procedure(name = "deposit.create.class", mode = Mode.WRITE)
	@Description("CALL deposit.create.class(name, order, position, comment, descriptors) YIELD node")
	public Stream<CreateService.ClassNodeResult> createClass(
			@Name("name") String name,
			@Name(value = "order", defaultValue = "0") Long order,
			@Name(value = "position", defaultValue = "[0.0, 0.0]") List<Double> position,
			@Name(value = "comment", defaultValue = "") String comment,
			@Name(value = "descriptors", defaultValue = "[]") List<List<String>> descriptors) {

		return createService.createClass(name, order, position, comment, descriptors);
	}

	/**
     * Creates a Class Descriptor with specified properties.
     *
     * @param className      the name of the class.
     * @param descriptorName the name of the descriptor.
     * @param type           the type of the descriptor.
     * @param order          the order of the descriptor, default is 0.
     * @param comment        the comment for the descriptor, default is an empty string.
     * @throws RuntimeException if there is an error during setting the descriptor.
     */
    @Procedure(name = "deposit.create.classDescriptor", mode = Mode.WRITE)
    @Description("CALL deposit.create.classDescriptor(className, descriptorName, type, order, comment)")
    public void createClassDescriptor(
            @Name("className") String className,
            @Name("descriptorName") String descriptorName,
            @Name("type") String type,
            @Name(value = "order", defaultValue = "0") Long order,
            @Name(value = "comment", defaultValue = "") String comment) {

		createService.createClassDescriptor(className, descriptorName, type, order, comment);
    }
}
