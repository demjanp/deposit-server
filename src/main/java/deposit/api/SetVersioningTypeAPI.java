package deposit.api;

import deposit.service.SetVersioningTypeService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class SetVersioningTypeAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Sets the versioning type.
	 *
	 * @param versioningType type of versioning to use. Can be empty, 'transient' or 'persistent', default is an empty string.
	 * @throws RuntimeException if there is an error during the transaction.
	 */
	@Procedure(name = "deposit.set.versioningType", mode = Mode.WRITE)
	@Description("CALL deposit.set.versioningType(versioningType)")
	public void setVersioningType(
			@Name("versioningType") String versioningType) {
		SetVersioningTypeService service = SetVersioningTypeService.getInstance();
		service.setVersioningType(tx, versioningType);
	}
}
