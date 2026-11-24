package deposit.service;

import deposit.common.*;
import deposit.repository.*;
import java.io.*;
import java.util.*;
import org.neo4j.graphdb.*;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/** Short-transaction adapter for the authenticated resource-download transport. */
public final class BrowserDownloadService {
    private final ServerContext server;
    private final GraphDatabaseAPI database;
    public BrowserDownloadService(ServerContext server,GraphDatabaseAPI database){this.server=server;this.database=database;}

    public record Login(String token,String csrfToken) {}
    public record Account(String username,String role,boolean administrative,String csrfToken) {}
    public record Range(long offset,long length,boolean partial) {}
    public static final class DownloadFailure extends RuntimeException {
        public final int status;public final String code;public final long rangeSize;
        private DownloadFailure(int status,String code,long rangeSize){super(code);this.status=status;this.code=code;this.rangeSize=rangeSize;}
    }
    public static final class ResourceRead implements AutoCloseable {
        public final InputStream stream;public final String filename,mediaType,checksum;public final long size,offset,length;public final boolean partial;
        private final AutoCloseable pin;
        private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
        private ResourceRead(InputStream stream,AutoCloseable pin,String filename,String mediaType,String checksum,long size,Range range){this.stream=stream;this.pin=pin;this.filename=filename;this.mediaType=mediaType;this.checksum=checksum;this.size=size;offset=range.offset;length=range.length;partial=range.partial;}
        @Override public void close()throws IOException {if(!closed.compareAndSet(false,true))return;try{stream.close();}finally{try{pin.close();}catch(Exception e){throw new IOException("Reader pin release failed",e);}}}
    }

    public Login login(String username,String password,String origin,String remoteAddress){
        try(var tx=database.beginTx()){
            RequestContext c=context(tx,null);String token=new AccountService(c).loginBrowser(username,password,remoteAddress==null?origin:remoteAddress);tx.commit();
            ServerContext.Session session=server.session(Json.hash(token));if(session==null||session.transport!=ServerContext.Transport.BROWSER)throw new IllegalStateException("Browser session was not committed");
            return new Login(token,session.csrfToken);
        }catch(RuntimeException e){throw translate(e);}
    }

    public Account account(String token){
        ServerContext.Session session;
        try(var tx=database.beginTx()){
            RequestContext c=context(tx,token);Node user=new BrowserService(c).account();
            String role=(String)user.getProperty("serverRole","user");boolean administrative=server.authenticateBrowser(c).administrative;
            Account result=new Account((String)user.getProperty("username"),role,administrative,c.session.csrfToken);tx.commit();session=c.session;
            if(!server.touchBrowserSession(token,session))throw failure(401,"AUTH_FAILED");return result;
        }catch(RuntimeException e){throw translate(e);}
    }

    public void logout(String token,String csrf){
        try(var tx=database.beginTx()){
            RequestContext c=context(tx,token);ServerContext.Session session=server.authenticateBrowser(c);checkCsrf(session,csrf);
            c.state.afterCommit.add(()->server.removeSession(Json.hash(token),session));tx.commit();
        }catch(RuntimeException e){throw translate(e);}
    }

    public Account administrativeMode(String token,String csrf,boolean enabled){
        ServerContext.Session session;
        try(var tx=database.beginTx()){
            RequestContext c=context(tx,token);session=server.authenticateBrowser(c);checkCsrf(session,csrf);
            CoreRepository repo=new CoreRepository(tx,c.timestamp);Node user=repo.find("User","id",c.actorId);
            if(enabled&&!AuthorizationAdapter.administrator(user))throw failure(403,"FORBIDDEN");
            boolean value=enabled;c.state.afterCommit.add(()->session.administrative=value);tx.commit();
        }catch(RuntimeException e){throw translate(e);}
        return account(token);
    }

    public ResourceRead openResource(String token,String namespaceId,String resourceId,String rangeHeader){
        AutoCloseable pin=null;
        try{
            try(var tx=database.beginTx()){
                RequestContext c=context(tx,token);new BrowserService(c).account();
                String ns=RequestContext.uuid(namespaceId),id=RequestContext.uuid(resourceId);CoreRepository core=new CoreRepository(tx,c.timestamp);ResourceRepository resources=new ResourceRepository(tx,core);
                core.lockNamespace(ns);resources.lock(id);Node resource=resources.resource(ns,id);
                String role=AuthorizationAdapter.role(c,core,ns);
                if(resource==null||role==null||!resources.accessible(resource,c.actorId,role,false))throw failure(404,"NOT_FOUND");
                if(!"ready".equals(resource.getProperty("state")))throw failure(409,"RESOURCE_NOT_READY");
                long size=ResourceRepository.number(resource,"bytes",0);Range range=parseRange(rangeHeader,size);
                pin=server.pinResource(id);resource.removeProperty("orphanSince");
                resources.accessible(resource,c.actorId,role,true);
                ResourceStorage.Content content=resources.content(resource);String filename=content.storedFilename();String media=(String)resource.getProperty("mediaType","application/octet-stream");String checksum=(String)resource.getProperty("checksum");
                tx.commit();if(!server.touchBrowserSession(token,c.session))throw failure(401,"AUTH_FAILED");
                InputStream stream=server.storage.content.open(content,range.offset,range.length);
                ResourceRead result=new ResourceRead(stream,pin,filename,media,checksum,size,range);pin=null;return result;
            }
        }catch(RuntimeException e){closeQuietly(pin);throw translate(e);}catch(IOException e){closeQuietly(pin);throw failure(503,"RESOURCE_CORRUPT");}
    }

    /** Called between bounded output chunks; no transaction or graph lock spans socket writes. */
    public boolean reauthorize(String token,String namespaceId,String resourceId){
        ServerContext.Session session;
        try(var tx=database.beginTx()){
            RequestContext c=context(tx,token);new BrowserService(c).account();String ns=RequestContext.uuid(namespaceId),id=RequestContext.uuid(resourceId);
            CoreRepository core=new CoreRepository(tx,c.timestamp);ResourceRepository resources=new ResourceRepository(tx,core);core.lockNamespace(ns);resources.lock(id);
            Node resource=resources.resource(ns,id);String role=AuthorizationAdapter.role(c,core,ns);
            if(resource==null||role==null||!"ready".equals(resource.getProperty("state"))||!resources.accessible(resource,c.actorId,role,false))return false;
            resources.accessible(resource,c.actorId,role,true);session=c.session;tx.commit();return server.touchBrowserSession(token,session);
        }catch(RuntimeException e){return false;}
    }

    public static Range parseRange(String header,long size){
        if(header==null||header.isBlank())return new Range(0,size,false);
        if(size<=0||!header.matches("(?i)bytes=[0-9]*-[0-9]*")||header.indexOf(',')>=0)throw rangeFailure(size);
        String value=header.substring(header.indexOf('=')+1),startText=value.substring(0,value.indexOf('-')),endText=value.substring(value.indexOf('-')+1);
        try{
            long start,end;
            if(startText.isEmpty()){long suffix=Long.parseLong(endText);if(suffix<=0)throw new NumberFormatException();long length=Math.min(suffix,size);start=size-length;end=size-1;}
            else{start=Long.parseLong(startText);end=endText.isEmpty()?size-1:Long.parseLong(endText);if(start<0||start>=size||end<start)throw new NumberFormatException();end=Math.min(end,size-1);}
            return new Range(start,end-start+1,true);
        }catch(NumberFormatException e){throw rangeFailure(size);}
    }

    private RequestContext context(Transaction tx,String token){return new RequestContext(tx,token==null?Map.of():Map.of("token",token));}
    private static void checkCsrf(ServerContext.Session session,String value){if(value==null||session.csrfToken==null||!java.security.MessageDigest.isEqual(session.csrfToken.getBytes(java.nio.charset.StandardCharsets.US_ASCII),value.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw failure(403,"CSRF_REJECTED");}
    private static DownloadFailure translate(RuntimeException e){if(e instanceof DownloadFailure f)return f;String message=e.getMessage();String code="INTERNAL";if(message!=null&&message.startsWith("DEPOSIT_ERROR "))try{code=(String)Json.read(message.substring("DEPOSIT_ERROR ".length())).get("code");}catch(Exception ignored){}
        int status=switch(code){case "AUTH_REQUIRED","AUTH_FAILED","SESSION_EXPIRED"->401;case "NOT_FOUND","FORBIDDEN","SCOPE_MISMATCH"->404;case "RESOURCE_NOT_READY"->409;case "RESOURCE_CORRUPT"->503;default->500;};return failure(status,code);}
    private static DownloadFailure failure(int status,String code){return new DownloadFailure(status,code,-1);}
    private static DownloadFailure rangeFailure(long size){return new DownloadFailure(416,"RANGE_NOT_SATISFIABLE",size);}
    private static void closeQuietly(AutoCloseable value){if(value!=null)try{value.close();}catch(Exception ignored){}}

    private static final class BrowserService extends BaseService {
        BrowserService(RequestContext c){super(c);}
        Node account(){return authenticateBrowser();}
    }
}
