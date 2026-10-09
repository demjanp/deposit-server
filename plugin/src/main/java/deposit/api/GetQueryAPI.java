package deposit.api;
import deposit.common.Results.*;
import deposit.service.GetQueryService;
import java.util.*;
import java.util.stream.Stream;
import org.neo4j.procedure.*;

public class GetQueryAPI extends BaseAPI {
    @Procedure(name="deposit.get.query",mode=Mode.READ)
    public Stream<QueryRowResult> getQuery(@Name("request") Map<String,Object> request,@Name("context") Map<String,Object> context){return invoke(context,c->new GetQueryService(c).getQuery(request));}
    @Procedure(name="deposit.get.queryPage",mode=Mode.READ)
    public Stream<DataRow> getQueryPage(@Name("request") Map<String,Object> request,@Name("context") Map<String,Object> context){return invoke(context,c->Stream.of(new GetQueryService(c).getQueryPage(request)));}
}
