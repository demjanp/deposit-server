package deposit.api;
import deposit.common.Results.*;
import deposit.service.HistoryService;
import java.util.*;
import java.util.stream.Stream;
import org.neo4j.procedure.*;

/** Public history procedures; all persistence/authorization is delegated. */
public final class HistoryAPI extends BaseAPI {
    @Procedure(name="deposit.get.revision",mode=Mode.READ) public Stream<DataRow> revision(@Name("request")Map<String,Object> r,@Name("context")Map<String,Object> c){return invoke(c,x->Stream.of(new HistoryService(x).revision(r)));}
    @Procedure(name="deposit.get.history",mode=Mode.READ) public Stream<DataRow> history(@Name("request")Map<String,Object> r,@Name("context")Map<String,Object> c){return invoke(c,x->new HistoryService(x).records(r));}
    @Procedure(name="deposit.get.historyPage",mode=Mode.READ) public Stream<DataRow> page(@Name("request")Map<String,Object> r,@Name("context")Map<String,Object> c){return invoke(c,x->Stream.of(new HistoryService(x).page(r)));}
    @Procedure(name="deposit.get.restorePreview",mode=Mode.READ) public Stream<DataRow> preview(@Name("request")Map<String,Object> r,@Name("context")Map<String,Object> c){return invoke(c,x->Stream.of(new HistoryService(x).restorePreview(r)));}
    @Procedure(name="deposit.utils.undo",mode=Mode.WRITE) public Stream<DataRow> undo(@Name("request")Map<String,Object> r,@Name("context")Map<String,Object> c){return invoke(c,x->Stream.of(new HistoryService(x).reverse("undo",r).structured(x)));}
    @Procedure(name="deposit.utils.redo",mode=Mode.WRITE) public Stream<DataRow> redo(@Name("request")Map<String,Object> r,@Name("context")Map<String,Object> c){return invoke(c,x->Stream.of(new HistoryService(x).reverse("redo",r).structured(x)));}
    @Procedure(name="deposit.utils.restore",mode=Mode.WRITE) public Stream<DataRow> restore(@Name("request")Map<String,Object> r,@Name("context")Map<String,Object> c){return invoke(c,x->Stream.of(new HistoryService(x).reverse("restore",r).structured(x)));}
    @Procedure(name="deposit.utils.canUndo",mode=Mode.READ) public Stream<HistoryService.CanUndoRow> canUndo(@Name("request")Map<String,Object> r,@Name("context")Map<String,Object> c){return invoke(c,x->Stream.of(new HistoryService(x).canUndo(r)));}
    @Procedure(name="deposit.utils.isRestored",mode=Mode.READ) public Stream<ValueRow> restored(@Name("request")Map<String,Object> r,@Name("context")Map<String,Object> c){return invoke(c,x->Stream.of(new HistoryService(x).restored(r)));}
    @Procedure(name="deposit.set.versioningType",mode=Mode.WRITE) public Stream<DataRow> mode(@Name("request")Map<String,Object> r,@Name("context")Map<String,Object> c){return invoke(c,x->Stream.of(new HistoryService(x).mode(r).structured(x)));}
    @Procedure(name="deposit.set.versionTag",mode=Mode.WRITE) public Stream<DataRow> tag(@Name("request")Map<String,Object> r,@Name("context")Map<String,Object> c){return invoke(c,x->Stream.of(new HistoryService(x).tag(r).structured(x)));}
}
