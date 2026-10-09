package deposit.api;
import deposit.common.Results.ValueRow;
import deposit.service.CancellationService;
import java.util.*;
import java.util.stream.Stream;
import org.neo4j.procedure.*;

public class CancellationAPI extends BaseAPI {
    @Procedure(name="deposit.utils.cancel",mode=Mode.READ)
    public Stream<ValueRow> cancel(@Name("request") Map<String,Object> request,@Name("context") Map<String,Object> context) {
        return invoke(context,c->Stream.of(new CancellationService(c).cancel(request)));
    }
}
