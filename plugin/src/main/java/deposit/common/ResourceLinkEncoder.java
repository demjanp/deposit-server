package deposit.common;

import java.net.*;

/** Stage 9 fixture surface for Stage 10's authenticated resource URL convention. */
public final class ResourceLinkEncoder {
    private ResourceLinkEncoder() {}
    public static String url(String publicBaseUrl,String namespaceId,String resourceId){
        String ns=RequestContext.uuid(namespaceId),resource=RequestContext.uuid(resourceId);
        try{
            URI base=new URI(publicBaseUrl);if(!base.isAbsolute()||base.getHost()==null||base.getRawQuery()!=null||base.getRawFragment()!=null||base.getUserInfo()!=null)throw new IllegalArgumentException();
            String path=base.getRawPath()==null?"":base.getRawPath();if(path.endsWith("/"))path=path.substring(0,path.length()-1);
            return new URI(base.getScheme(),null,base.getHost(),base.getPort(),path+"/resources/"+ns+"/"+resource,null,null).toASCIIString();
        }catch(URISyntaxException|RuntimeException e){throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid configured resource URL");}
    }
    public static String markdown(String publicBaseUrl,String namespaceId,String resourceId,String storedFilename){
        String label=storedFilename.replace("\\","\\\\").replace("[","\\[").replace("]","\\]");
        String target=url(publicBaseUrl,namespaceId,resourceId).replace("(","%28").replace(")","%29");
        return "["+label+"]("+target+")";
    }
}
