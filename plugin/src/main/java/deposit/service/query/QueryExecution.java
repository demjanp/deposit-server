package deposit.service.query;

import deposit.common.*;
import deposit.common.QueryContracts.Binding;
import deposit.common.QueryContracts.Cell;
import java.util.*;
import java.util.stream.*;
import static deposit.service.query.QueryParser.*;
import static deposit.service.query.QueryTranslator.*;
import static deposit.service.query.QueryValues.*;

/** Typed operations shared by native streams and portable pages. */
public final class QueryExecution {
    public record Row(List<Cell> cells,List<String> ids,String groupKey) {}
    private final QueryPlan plan;private final Runnable check;
    public QueryExecution(QueryPlan plan,Runnable check){this.plan=plan;this.check=check;}
    public Stream<Row> execute(Stream<Binding> input){return execute(input,0);}
    public Stream<Row> execute(Stream<Binding> input,long offset){
        boolean skipBindings=!plan.aggregate&&!plan.collect&&!plan.parsed.distinct()&&plan.order.isEmpty();
        Stream<Binding> filtered=afterOffset(input.filter(b->{
            check.run();
            return Boolean.TRUE.equals(test(plan.parsed.where(),b));
        }),skipBindings?offset:0);
        Stream<Row> rows;
        if(plan.aggregate){var groups=new TreeMap<String,List<Binding>>();try(filtered){filtered.forEach(b->{check.run();var keys=new ArrayList<Cell>();for(Projection p:plan.projections)if(p.item().function()==null)keys.add(cell(p,b));groups.computeIfAbsent(key(keys),k->new ArrayList<>()).add(b);});}
            if(groups.isEmpty()&&plan.projections.stream().allMatch(p->p.item().function()!=null))groups.put(key(List.of()),List.of());
            rows=groups.entrySet().stream().map(e->{check.run();var cells=new ArrayList<Cell>();for(Projection p:plan.projections)cells.add(p.item().function()==null?cell(p,e.getValue().getFirst()):aggregate(p,e.getValue()));return new Row(cells,List.of(),e.getKey());});
        }else{rows=filtered.map(b->new Row(plan.projections.stream().map(p->cell(p,b)).toList(),b.ids(),null));if(plan.collect)rows=rows.filter(r->r.cells.getFirst().value()!=null);}
        if(plan.parsed.distinct()||plan.collect&&plan.projections.getFirst().item().distinct()){var seen=new HashSet<String>();rows=rows.filter(r->{check.run();return seen.add(key(r.cells));});}
        if(!plan.order.isEmpty())rows=rows.sorted(this::compareRows);
        return afterOffset(rows.peek(r->check.run()),skipBindings?0:offset);
    }
    private <T> Stream<T> afterOffset(Stream<T> source,long offset) {
        if(offset==0)return source;
        var iterator=source.iterator();
        try {
            for(long i=0;i<offset;i++) {
                check.run();
                if(!iterator.hasNext())throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid cursor offset");
                iterator.next();
            }
        } catch(RuntimeException failure) {
            source.close();
            throw failure;
        }
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator,Spliterator.ORDERED),false).onClose(source::close);
    }
    private int compareRows(Row a,Row b){check.run();for(int i=0;i<plan.order.size();i++){int index=plan.order.get(i);Cell x=a.cells.get(index),y=b.cells.get(index);int cmp=compare(x.type(),x.value(),y.value());if(cmp!=0){if(x.value()!=null&&y.value()!=null&&plan.parsed.order().get(i).descending())cmp=-cmp;return cmp;}}
        if(a.groupKey!=null)return a.groupKey.compareTo(b.groupKey);for(int i=0;i<a.ids.size();i++){int c=a.ids.get(i).compareTo(b.ids.get(i));if(c!=0)return c;}return 0;}
    private Cell cell(Projection p,Binding b){return p.field()==null?new Cell("Object",b.objects().get(p.binding()),true):b.cell(p.field());}
    private Boolean test(Expr e,Binding b){check.run();if(e==null)return true;if(e instanceof Relation r)return (Boolean)b.raw().get(plan.relations.get(r));if(e instanceof Logic l){Boolean x=test(l.left(),b);if(l.op().equals("NOT"))return x==null?null:!x;Boolean y=test(l.right(),b);if(l.op().equals("AND")){if(Boolean.FALSE.equals(x)||Boolean.FALSE.equals(y))return false;if(x==null||y==null)return null;return true;}if(Boolean.TRUE.equals(x)||Boolean.TRUE.equals(y))return true;if(x==null||y==null)return null;return false;}
        Predicate p=(Predicate)e;Cell c=b.cell(plan.predicates.get(p));Object a=c.value(),v=plan.values.get(p);String op=p.op();if(op.startsWith("IS")){boolean result=op.endsWith("MISSING")?!c.present():a==null;return op.contains("NOT")?!result:result;}
        if(op.equals("IN")){if(v==null)return null;boolean unknown=false;for(Object x:(List<?>)v){if(a==null||x==null)unknown=true;else if(equal(c.type(),a,x))return true;}return unknown?null:false;}
        if(a==null||v==null)return null;if(Set.of("=","!=","<>").contains(op)){boolean eq=equal(c.type(),a,v);return op.equals("=")?eq:!eq;}int cmp=compare(c.type(),a,v);return switch(op){case "<"->cmp<0;case "<="->cmp<=0;case ">"->cmp>0;case ">="->cmp>=0;default->throw new IllegalStateException();};
    }
    private Cell aggregate(Projection p,List<Binding> bindings){String fn=p.item().function();var cells=new ArrayList<Cell>();var seen=new HashSet<String>();for(Binding b:bindings){check.run();Cell c=cell(p,b);if(c.value()!=null&&(!p.item().distinct()||seen.add(key(c))))cells.add(c);}
        Object result;if(fn.equals("COUNT"))result=(long)cells.size();else if(fn.equals("MIN")||fn.equals("MAX")){Cell best=null;for(Cell c:cells){check.run();if(best==null||(fn.equals("MIN")?compare(c.type(),c.value(),best.value())<0:compare(c.type(),c.value(),best.value())>0))best=c;}result=best==null?null:best.value();}
        else if(cells.isEmpty())result=fn.equals("SUM")?(p.type().equals("Integer")?(Object)0L:0.0):fn.equals("STDEV")?0.0:null;
        else if(fn.equals("SUM")&&p.type().equals("Integer")){long sum=0;try{for(Cell c:cells){check.run();sum=Math.addExact(sum,((Number)c.value()).longValue());}}catch(ArithmeticException ex){throw limit();}result=sum;}
        else{double sum=0,mean=0,m2=0;int n=0;for(Cell c:cells){check.run();double x=((Number)c.value()).doubleValue();sum+=x;n++;double delta=x-mean;mean+=delta/n;m2+=delta*(x-mean);}
            result=switch(fn){case "SUM"->sum;case "AVG"->mean;case "STDEV"->n<2?0.0:Math.sqrt(m2/(n-1));case "PERCENTILE"->{cells.sort((a,b)->{check.run();return compare(a.type(),a.value(),b.value());});double rank=p.item().percentile()*(n-1);int low=(int)rank,high=(int)Math.ceil(rank);double x=((Number)cells.get(low).value()).doubleValue(),y=((Number)cells.get(high).value()).doubleValue();yield low==high?x:x*(1-(rank-low))+y*(rank-low);}default->throw new IllegalStateException();};if(!Double.isFinite((Double)result))throw limit();}
        return new Cell(p.type(),result,true);
    }
    private static RuntimeException limit(){return ExceptionHandler.fail("LIMIT_EXCEEDED","Aggregate overflow or nonfinite result");}
}
