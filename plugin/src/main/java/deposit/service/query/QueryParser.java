package deposit.service.query;

import deposit.common.ExceptionHandler;
import java.util.*;

/** Complete DQL grammar. Quoted names and literals are distinct token categories. */
public final class QueryParser {
    public record Ref(String object,String field) {}
    public record Select(Ref ref,String function,boolean distinct,Double percentile,String alias) {}
    public record Order(Ref ref,boolean descending) {}
    public sealed interface Expr permits Logic,Predicate,Relation {}
    public record Logic(String op,Expr left,Expr right) implements Expr {}
    public record Predicate(Ref ref,String op,Object value) implements Expr {}
    public record Relation(String left,String right,String label,int min,int max,int direction) implements Expr {}
    public record Parameter(String name) {}
    public record ParsedQuery(boolean distinct,List<Select> select,Expr where,List<Order> order) {}
    private record Token(String text,String kind,int offset) {}
    private final List<Token> tokens=new ArrayList<>(); private int at,depth;private final Runnable check;
    private QueryParser(String text,Runnable check) {
        this.check=check;
        for(int i=0;i<text.length();) {
            if((tokens.size()&63)==0)check.run();if(tokens.size()>8192)throw limit();char c=text.charAt(i);if(Character.isWhitespace(c)){i++;continue;}int start=i;
            if(c=='\''||c=='"') {char quote=c;var out=new StringBuilder();i++;boolean closed=false;
                while(i<text.length()){if((i&1023)==0)check.run();c=text.charAt(i++);if(c==quote){if(i<text.length()&&text.charAt(i)==quote){out.append(c);i++;}else{closed=true;break;}}else out.append(c);}
                if(!closed)throw invalid("Unterminated quoted token at "+start);tokens.add(new Token(out.toString(),quote=='"'?"name":"string",start));continue;}
            if(Character.isLetter(c)||c=='_'){i++;while(i<text.length()&&(Character.isLetterOrDigit(text.charAt(i))||text.charAt(i)=='_'))i++;tokens.add(new Token(text.substring(start,i),"id",start));continue;}
            if(Character.isDigit(c)){i++;while(i<text.length()&&Character.isDigit(text.charAt(i)))i++;
                if(i<text.length()&&text.charAt(i)=='.'&&(i+1>=text.length()||text.charAt(i+1)!='.')){i++;while(i<text.length()&&Character.isDigit(text.charAt(i)))i++;}
                if(i<text.length()&&(text.charAt(i)=='e'||text.charAt(i)=='E')){i++;if(i<text.length()&&(text.charAt(i)=='+'||text.charAt(i)=='-'))i++;int exp=i;while(i<text.length()&&Character.isDigit(text.charAt(i)))i++;if(i==exp)throw invalid("Invalid exponent");}
                tokens.add(new Token(text.substring(start,i),"number",start));continue;}
            String pair=i+1<text.length()?text.substring(i,i+2):"";
            if(Set.of("->","<-","..",">=","<=","<>","!=").contains(pair)){tokens.add(new Token(pair,"symbol",i));i+=2;continue;}
            if(".,()[]$*+-=<>".indexOf(c)<0)throw invalid("Unexpected character at "+i);
            tokens.add(new Token(String.valueOf(c),"symbol",i++));
        }tokens.add(new Token("<end>","end",text.length()));
    }
    public static ParsedQuery parse(String query){return parse(query,()->{});}
    public static ParsedQuery parse(String query,Runnable check){if(query==null||query.isBlank())throw invalid("query must not be empty");return new QueryParser(query,check).query();}
    private ParsedQuery query(){expect("SELECT");boolean distinct=take("DISTINCT");var select=new ArrayList<Select>();
        do {String fn=null,alias=null;boolean d=false;Double p=null;Ref ref;
            if(peek().kind.equals("id")&&Set.of("COUNT","SUM","AVG","MIN","MAX","STDEV","PERCENTILE","COLLECT").contains(peek().text)&&tokens.get(at+1).text.equals("(")){
                fn=next().text;expect("(");d=take("DISTINCT");ref=ref();if(take(","))p=number().doubleValue();expect(")");expect("AS");alias=alias();
                if(fn.equals("PERCENTILE")?(p==null||p<0||p>1):p!=null)throw invalid("Invalid aggregate argument");
            }else{ref=ref();if(take("AS"))alias=alias();}select.add(new Select(ref,fn,d,p,alias));
        }while(take(","));Expr where=take("WHERE")?or():null;var order=new ArrayList<Order>();if(take("ORDER")){expect("BY");do{Ref ref=ref();boolean desc=take("DESC");if(!desc)take("ASC");order.add(new Order(ref,desc));}while(take(","));}
        if(!peek().kind.equals("end"))throw invalid("Trailing query text at "+peek().offset);return new ParsedQuery(distinct,List.copyOf(select),where,List.copyOf(order));}
    private Expr or(){var items=new ArrayList<Expr>();items.add(and());while(take("OR"))items.add(and());return combine("OR",items,0,items.size());}
    private Expr and(){var items=new ArrayList<Expr>();items.add(not());while(take("AND"))items.add(not());return combine("AND",items,0,items.size());}
    private Expr combine(String op,List<Expr> items,int from,int to){if(to-from==1)return items.get(from);int middle=(from+to)/2;return new Logic(op,combine(op,items,from,middle),combine(op,items,middle,to));}
    private Expr not(){if(++depth>128)throw limit();try{if(take("NOT"))return new Logic("NOT",not(),null);if(take("(")){Expr e=or();expect(")");return e;}return predicate();}finally{depth--;}}
    private Expr predicate(){Ref ref=ref();if(ref.field==null&&(is("-")||is("<-"))){boolean incoming=take("<-");if(!incoming)expect("-");String label=null;if(peek().kind.equals("id")||peek().kind.equals("name"))label=name();int min=1,max=1;
            if(take("*")){min=hop();expect("..");max=hop();if(min<1||max<min||max>10)throw invalid("Path bounds must be 1..10");}
            boolean outgoing=take("->");if(!outgoing)expect("-");if(incoming&&outgoing)throw invalid("Invalid relationship direction");return new Relation(ref.object,name(),label,min,max,incoming?-1:outgoing?1:0);}
        if(take("IS")){boolean neg=take("NOT");String op=take("NULL")?"NULL":take("MISSING")?"MISSING":null;if(op==null)throw invalid("Expected NULL or MISSING");return new Predicate(ref,"IS "+(neg?"NOT ":"")+op,null);}
        String op=next().text;if(!Set.of("=","<>","!=",">",">=","<","<=","IN").contains(op))throw invalid("Expected comparison");return new Predicate(ref,op,value());}
    private int hop(){Token t=next();if(!t.kind.equals("number")||!t.text.matches("[0-9]+"))throw invalid("Expected integer hop bound");try{return Integer.parseInt(t.text);}catch(NumberFormatException e){throw invalid("Hop bound too large");}}
    private Object value(){if(++depth>128)throw limit();try{if(take("$"))return new Parameter(alias());if(take("NULL"))return null;if(take("TRUE"))return true;if(take("FALSE"))return false;if(take("[")){var list=new ArrayList<Object>();if(!take("]")){do{list.add(value());}while(take(","));expect("]");}return list;}if(peek().kind.equals("string"))return next().text;return number();}finally{depth--;}}
    private Number number(){String sign=take("-")?"-":take("+")?"+":"";Token t=next();if(!t.kind.equals("number"))throw invalid("Expected value");try{String s=sign+t.text;if(s.matches("[+-]?[0-9]+"))return Long.valueOf(s);double v=Double.parseDouble(s);if(!Double.isFinite(v))throw invalid("Nonfinite number");return v;}catch(NumberFormatException e){throw invalid("Number out of range");}}
    private Ref ref(){String object=name();return new Ref(object,take(".")?name():null);}
    private String name(){Token t=next();if(!Set.of("name","id").contains(t.kind)||t.kind.equals("id")&&Set.of("SELECT","WHERE","ORDER","BY","AS","AND","OR","NOT","IS","NULL","TRUE","FALSE","IN","ASC","DESC","DISTINCT").contains(t.text))throw invalid("Expected identifier at "+t.offset);return t.text;}
    private String alias(){Token t=peek();if(!t.kind.equals("id"))throw invalid("Alias must be an unquoted identifier");return name();}
    private Token peek(){return tokens.get(at);}private Token next(){Token t=peek();if(at<tokens.size()-1)at++;return t;}
    private boolean is(String text){Token t=peek();return !Set.of("name","string").contains(t.kind)&&t.text.equals(text);}private boolean take(String text){if(!is(text))return false;at++;return true;}private void expect(String text){if(!take(text))throw invalid("Expected "+text+" at "+peek().offset);}
    private static RuntimeException limit(){return ExceptionHandler.fail("LIMIT_EXCEEDED","Query syntax complexity exceeded");}
    public static RuntimeException invalid(String text){return ExceptionHandler.fail("VALIDATION_FAILED",text);}
}
