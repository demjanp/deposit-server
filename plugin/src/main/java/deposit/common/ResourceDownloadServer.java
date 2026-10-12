package deposit.common;

import com.sun.net.httpserver.*;
import deposit.service.BrowserDownloadService;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/** Narrow authenticated browser transport for immutable Deposit resources. */
public final class ResourceDownloadServer implements AutoCloseable {
    private static final int MAX_FORM_BYTES=16*1024,CHUNK_BYTES=1024*1024;
    private static final String SESSION_COOKIE="Deposit-Session",LOGIN_CSRF_COOKIE="Deposit-Login-Csrf";
    private static final System.Logger LOG=System.getLogger(ResourceDownloadServer.class.getName());
    private final ServerContext server;private final BrowserDownloadService service;private final URI publicBase;
    private final String prefix,cookiePath,origin;private final InetSocketAddress bindAddress;private final boolean secure;
    private HttpServer listener;private ExecutorService executor;private volatile boolean stopping;

    public ResourceDownloadServer(ServerContext server,GraphDatabaseAPI database)throws Exception{
        this.server=server;service=new BrowserDownloadService(server,database);
        Properties config=new Properties();try(InputStream in=Files.newInputStream(Path.of(System.getProperty("deposit.config")))){config.load(in);}
        InetAddress address=InetAddress.getByName(config.getProperty("download.listenAddress"));int port=Integer.parseInt(config.getProperty("download.port"));bindAddress=new InetSocketAddress(address,port);
        publicBase=URI.create(config.getProperty("download.publicBaseUrl"));secure="https".equalsIgnoreCase(publicBase.getScheme());
        prefix=publicBase.getRawPath()==null?"":trimSlashes(publicBase.getRawPath());cookiePath=prefix.isEmpty()?"/":prefix+"/";
        int defaultPort=secure?443:80;String host=publicBase.getHost().contains(":")?"["+publicBase.getHost()+"]":publicBase.getHost();
        origin=publicBase.getScheme()+"://"+host+(publicBase.getPort()<0||publicBase.getPort()==defaultPort?"":":"+publicBase.getPort());
        if(secure&&config.containsKey("download.tlsKeyStore"))listener=createHttps(config);else listener=HttpServer.create(bindAddress,64);
        listener.createContext("/",this::handle);
        executor=Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("deposit-download-http-",0).factory());listener.setExecutor(executor);
    }
    public String publicBaseUrl(){return publicBase.toASCIIString();}
    public void start(){if(stopping)throw new IllegalStateException("Download listener is stopping");listener.start();}
    private static HttpsServer createHttps(Properties config)throws Exception{
        HttpsServer https=HttpsServer.create(new InetSocketAddress(InetAddress.getByName(config.getProperty("download.listenAddress")),Integer.parseInt(config.getProperty("download.port"))),64);
        char[] password=Files.readString(Path.of(config.getProperty("download.tlsKeyStorePasswordFile")),StandardCharsets.UTF_8).strip().toCharArray();
        try{KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());try(InputStream in=Files.newInputStream(Path.of(config.getProperty("download.tlsKeyStore")))){store.load(in,password);}KeyManagerFactory managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());managers.init(store,password);SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(managers.getKeyManagers(),null,null);https.setHttpsConfigurator(new HttpsConfigurator(ssl));return https;}finally{Arrays.fill(password,'\0');}
    }

    private void handle(HttpExchange exchange){
        String diagnostic=UUID.randomUUID().toString();
        try{
            if(stopping){sendText(exchange,503,"Download listener is stopping");return;}
            secureHeaders(exchange.getResponseHeaders());String route=route(exchange.getRequestURI());String method=exchange.getRequestMethod();
            if(route.startsWith("/resources/")){if(!method.equals("GET")){methodNotAllowed(exchange,"GET");return;}resource(exchange,route);return;}
            switch(route){
                case "/login"->{if(method.equals("GET"))loginPage(exchange);else if(method.equals("POST"))login(exchange);else methodNotAllowed(exchange,"GET, POST");}
                case "/logout"->{if(!method.equals("POST")){methodNotAllowed(exchange,"POST");return;}logout(exchange);}
                case "/administrative-mode"->{if(!method.equals("POST")){methodNotAllowed(exchange,"POST");return;}administrative(exchange);}
                default->sendText(exchange,404,"Not found");
            }
        }catch(BrowserDownloadService.DownloadFailure e){
            if(e.status==416){exchange.getResponseHeaders().set("Content-Range","bytes */"+e.rangeSize);sendText(exchange,416,"Range not satisfiable");}
            else sendText(exchange,e.status,safeMessage(e.status));
        }catch(IllegalArgumentException e){sendText(exchange,400,"Invalid request");}
        catch(IOException e){LOG.log(System.Logger.Level.WARNING,"Download request ended before completion; reference {0}",diagnostic);closeExchange(exchange);}
        catch(Throwable e){LOG.log(System.Logger.Level.ERROR,"Download request failed; reference {0}",diagnostic);sendText(exchange,500,"Request failed; reference "+diagnostic);}
        finally{closeExchange(exchange);}
    }

    private String route(URI uri){
        String path=uri.getRawPath();if(path==null||!path.startsWith(prefix))return "/__not_found";
        String route=path.substring(prefix.length());if(route.isEmpty())return "/";if(!route.startsWith("/"))return "/__not_found";return route;
    }
    private void resource(HttpExchange exchange,String route)throws IOException{
        String[] parts=route.substring("/resources/".length()).split("/",-1);if(parts.length!=2){sendText(exchange,404,"Not found");return;}
        String namespace=canonical(parts[0]),resource=canonical(parts[1]);String token=cookie(exchange,SESSION_COOKIE);
        if(token==null){redirectLogin(exchange,absoluteRoute(route));return;}
        BrowserDownloadService.ResourceRead read;
        try{read=service.openResource(token,namespace,resource,exchange.getRequestHeaders().getFirst("Range"));}
        catch(BrowserDownloadService.DownloadFailure failure){if(failure.status==401){clearSession(exchange);redirectLogin(exchange,absoluteRoute(route));return;}if(failure.status==416)exchange.getResponseHeaders().set("Content-Range","bytes */"+failure.rangeSize);throw failure;}
        try(read){
            Headers headers=exchange.getResponseHeaders();headers.set("Content-Type",safeMediaType(read.mediaType));headers.set("Content-Length",Long.toString(read.length));headers.set("Content-Disposition",attachment(read.filename));headers.set("Cache-Control","private, no-store");headers.set("ETag","\""+read.checksum+"\"");headers.set("Connection","close");
            if(read.partial)headers.set("Content-Range","bytes "+read.offset+"-"+(read.offset+read.length-1)+"/"+read.size);
            exchange.sendResponseHeaders(read.partial?206:200,read.length==0?-1:read.length);
            OutputStream out=exchange.getResponseBody();boolean complete=false;
            try{
                byte[] buffer=new byte[CHUNK_BYTES];long sent=0;
                while(sent<read.length){if(stopping||!service.reauthorize(token,namespace,resource))return;int allowed=(int)Math.min(buffer.length,read.length-sent);int count=read.stream.readNBytes(buffer,0,allowed);if(count<=0)throw new IOException("Resource stream ended early");out.write(buffer,0,count);out.flush();sent+=count;}
                if(read.stream.read()!=-1)throw new IOException("Resource stream exceeded its declared length");out.flush();complete=true;
            }finally{
                if(!complete)closeExchange(exchange);
                try{out.close();}catch(IOException ignored){}
                if(complete)closeExchange(exchange);
            }
        }
    }
    private long safeSize(String token,String namespace,String resource){try(var read=service.openResource(token,namespace,resource,null)){return read.size;}catch(Exception ignored){return 0;}}
    private String absoluteRoute(String route){return prefix+route;}

    private void loginPage(HttpExchange exchange)throws IOException{
        String returnPath=validatedReturn(exchange.getRequestURI().getRawQuery());String token=cookie(exchange,SESSION_COOKIE);
        if(token!=null)try{var account=service.account(token);sendHtml(exchange,200,accountPage(account));return;}catch(BrowserDownloadService.DownloadFailure ignored){clearSession(exchange);}
        String csrf=ServerContext.randomToken();exchange.getResponseHeaders().add("Set-Cookie",cookieHeader(LOGIN_CSRF_COOKIE,csrf,false,"Strict"));
        sendHtml(exchange,200,loginForm(returnPath,csrf,false));
    }
    private void login(HttpExchange exchange)throws IOException{
        if(!sameOrigin(exchange)){sendText(exchange,403,"Request rejected");return;}
        Map<String,String> form=form(exchange);String csrfCookie=cookie(exchange,LOGIN_CSRF_COOKIE);if(!constantEquals(csrfCookie,form.get("csrf"))){sendText(exchange,403,"Request rejected");return;}
        String returnPath=validatedPath(form.get("return"));
        try{
            String token=service.login(form.get("username"),form.get("password"),origin,exchange.getRemoteAddress()==null?null:exchange.getRemoteAddress().getAddress().getHostAddress()).token();
            var account=service.account(token);Headers headers=exchange.getResponseHeaders();headers.add("Set-Cookie",cookieHeader(SESSION_COOKIE,token,true,"Lax"));headers.add("Set-Cookie",clearCookie(LOGIN_CSRF_COOKIE,false,"Strict"));redirect(exchange,returnPath);
        }catch(BrowserDownloadService.DownloadFailure failure){
            String csrf=ServerContext.randomToken();exchange.getResponseHeaders().add("Set-Cookie",cookieHeader(LOGIN_CSRF_COOKIE,csrf,false,"Strict"));sendHtml(exchange,200,loginForm(returnPath,csrf,true));
        }
    }
    private void logout(HttpExchange exchange)throws IOException{
        if(!sameOrigin(exchange)){sendText(exchange,403,"Request rejected");return;}String token=cookie(exchange,SESSION_COOKIE);if(token==null){clearSession(exchange);redirect(exchange,prefix+"/login");return;}
        Map<String,String> form=form(exchange);service.logout(token,form.get("csrf"));clearSession(exchange);redirect(exchange,prefix+"/login");
    }
    private void administrative(HttpExchange exchange)throws IOException{
        if(!sameOrigin(exchange)){sendText(exchange,403,"Request rejected");return;}String token=cookie(exchange,SESSION_COOKIE);if(token==null){sendText(exchange,401,"Authentication required");return;}
        Map<String,String> form=form(exchange);String enabled=form.get("enabled");if(!"true".equals(enabled)&&!"false".equals(enabled)){sendText(exchange,400,"Invalid request");return;}
        var account=service.administrativeMode(token,form.get("csrf"),Boolean.parseBoolean(enabled));sendHtml(exchange,200,accountPage(account));
    }

    private String accountPage(BrowserDownloadService.Account account){
        String state=account.administrative()?"enabled":"disabled";String toggle=account.administrative()?"false":"true";String action=account.administrative()?"Disable":"Enable";
        String admin=account.role().equals("administrator")?"<form method=\"post\" action=\""+prefix+"/administrative-mode\"><input type=\"hidden\" name=\"csrf\" value=\""+escape(account.csrfToken())+"\"><input type=\"hidden\" name=\"enabled\" value=\""+toggle+"\"><button>"+action+" administrative mode (currently "+state+")</button></form>":"";
        return page("Deposit downloads","<h1>Deposit downloads</h1><p>Signed in as "+escape(account.username())+".</p>"+admin+"<form method=\"post\" action=\""+prefix+"/logout\"><input type=\"hidden\" name=\"csrf\" value=\""+escape(account.csrfToken())+"\"><button>Log out</button></form>");
    }
    private String loginForm(String returnPath,String csrf,boolean failed){
        return page("Deposit login","<h1>Deposit login</h1>"+(failed?"<p role=\"alert\">Login failed. Check your username and password.</p>":"")+"<form method=\"post\" action=\""+prefix+"/login\"><input type=\"hidden\" name=\"csrf\" value=\""+escape(csrf)+"\"><input type=\"hidden\" name=\"return\" value=\""+escape(returnPath)+"\"><label>Username <input name=\"username\" autocomplete=\"username\" required></label><label>Password <input type=\"password\" name=\"password\" autocomplete=\"current-password\" required></label><button>Sign in</button></form>");
    }
    private String page(String title,String body){return "<!doctype html><html><head><meta charset=\"utf-8\"><title>"+escape(title)+"</title></head><body>"+body+"</body></html>";}

    private Map<String,String> form(HttpExchange exchange)throws IOException{
        String type=exchange.getRequestHeaders().getFirst("Content-Type");if(type==null||!type.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded"))throw new IllegalArgumentException("Form required");
        byte[] bytes=exchange.getRequestBody().readNBytes(MAX_FORM_BYTES+1);if(bytes.length>MAX_FORM_BYTES)throw new IllegalArgumentException("Form too large");Map<String,String> result=new HashMap<>();String text=new String(bytes,StandardCharsets.UTF_8);
        for(String pair:text.split("&")){int equals=pair.indexOf('=');String key=decode(equals<0?pair:pair.substring(0,equals)),value=decode(equals<0?"":pair.substring(equals+1));if(result.putIfAbsent(key,value)!=null)throw new IllegalArgumentException("Duplicate form field");}return result;
    }
    private String validatedReturn(String query){if(query==null)return prefix+"/";for(String item:query.split("&")){int eq=item.indexOf('=');if(eq>=0&&decode(item.substring(0,eq)).equals("return"))return validatedPath(decode(item.substring(eq+1)));}return prefix+"/";}
    private String validatedPath(String value){if(value==null||value.length()>2048||!value.startsWith(prefix+"/resources/")||value.startsWith("//")||value.contains("\\")||value.contains("\r")||value.contains("\n"))return prefix+"/";try{URI path=URI.create(value);if(path.isAbsolute()||path.getRawAuthority()!=null||path.getRawQuery()!=null||path.getRawFragment()!=null)return prefix+"/";String[] parts=path.getRawPath().substring((prefix+"/resources/").length()).split("/",-1);return parts.length==2?prefix+"/resources/"+canonical(parts[0])+"/"+canonical(parts[1]):prefix+"/";}catch(Exception e){return prefix+"/";}}
    private boolean sameOrigin(HttpExchange exchange){return origin.equals(exchange.getRequestHeaders().getFirst("Origin"));}
    private String cookie(HttpExchange exchange,String name){String value=null;for(String header:exchange.getRequestHeaders().getOrDefault("Cookie",List.of()))for(String item:header.split(";")){String[] pair=item.strip().split("=",2);if(pair.length==2&&pair[0].equals(name)){if(value!=null)return null;value=pair[1];}}return value;}
    private String cookieHeader(String name,String value,boolean httpOnly,String sameSite){return name+"="+value+"; Path="+cookiePath+"; SameSite="+sameSite+(httpOnly?"; HttpOnly":"")+(secure?"; Secure":"");}
    private String clearCookie(String name,boolean httpOnly,String sameSite){return name+"=; Path="+cookiePath+"; Max-Age=0; SameSite="+sameSite+(httpOnly?"; HttpOnly":"")+(secure?"; Secure":"");}
    private void clearSession(HttpExchange exchange){exchange.getResponseHeaders().add("Set-Cookie",clearCookie(SESSION_COOKIE,true,"Lax"));exchange.getResponseHeaders().add("Set-Cookie",clearCookie(LOGIN_CSRF_COOKIE,false,"Strict"));}
    private void redirectLogin(HttpExchange exchange,String returnPath)throws IOException{clearSession(exchange);redirect(exchange,prefix+"/login?return="+URLEncoder.encode(returnPath,StandardCharsets.UTF_8));}
    private void redirect(HttpExchange exchange,String target)throws IOException{exchange.getResponseHeaders().set("Location",target);exchange.sendResponseHeaders(303,-1);}
    private void secureHeaders(Headers headers){headers.set("X-Content-Type-Options","nosniff");headers.set("Referrer-Policy","no-referrer");headers.set("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");}
    private void sendHtml(HttpExchange exchange,int status,String html)throws IOException{byte[] bytes=html.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Referrer-Policy","same-origin");exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("Content-Length",Integer.toString(bytes.length));exchange.sendResponseHeaders(status,bytes.length);try(OutputStream out=exchange.getResponseBody()){out.write(bytes);}}
    private void sendText(HttpExchange exchange,int status,String message){try{byte[] bytes=(message+"\n").getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("Content-Length",Integer.toString(bytes.length));exchange.sendResponseHeaders(status,bytes.length);try(OutputStream out=exchange.getResponseBody()){out.write(bytes);}}catch(IOException ignored){}}
    private void methodNotAllowed(HttpExchange exchange,String allowed){exchange.getResponseHeaders().set("Allow",allowed);sendText(exchange,405,"Method not allowed");}
    private static String safeMessage(int status){return switch(status){case 404->"Not found";case 409->"Resource is not ready";case 503->"Resource unavailable";case 401->"Authentication required";case 403->"Request rejected";default->"Request failed";};}
    private static String safeMediaType(String media){return media!=null&&media.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(?:\\s*;\\s*charset=[A-Za-z0-9._-]+)?")?media:"application/octet-stream";}
    private static String attachment(String filename){String safe=filename==null?"download":filename.replaceAll("[\\r\\n\\\"\\\\]","_");String ascii=safe.replaceAll("[^\\x20-\\x7E]","_");String encoded=URLEncoder.encode(filename==null?"download":filename,StandardCharsets.UTF_8).replace("+","%20");return "attachment; filename=\""+ascii+"\"; filename*=UTF-8''"+encoded;}
    private static String canonical(String id){try{return RequestContext.uuid(id);}catch(Exception e){throw new IllegalArgumentException("Invalid resource path");}}
    private static String decode(String value){return URLDecoder.decode(value,StandardCharsets.UTF_8);}
    private static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    private static boolean constantEquals(String left,String right){return left!=null&&right!=null&&java.security.MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),right.getBytes(StandardCharsets.US_ASCII));}
    private static String trimSlashes(String path){int end=path.length();while(end>0&&path.charAt(end-1)=='/')end--;return path.substring(0,end);}
    private static void closeExchange(HttpExchange exchange){try{exchange.close();}catch(Exception ignored){}}

    @Override public void close(){stopping=true;if(listener!=null)listener.stop(1);if(executor!=null){executor.shutdown();try{if(!executor.awaitTermination(5,TimeUnit.SECONDS))executor.shutdownNow();}catch(InterruptedException e){executor.shutdownNow();Thread.currentThread().interrupt();}}}
}
