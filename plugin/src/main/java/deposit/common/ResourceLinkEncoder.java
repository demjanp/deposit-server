package deposit.common;

import java.net.*;

/** Encodes authenticated resource URLs used by browser downloads. */
public final class ResourceLinkEncoder {
    private ResourceLinkEncoder() {}
    public static String url(String publicBaseUrl,String namespaceId,String resourceId){
        String ns=RequestContext.uuid(namespaceId),resource=RequestContext.uuid(resourceId);
        try{
            URI base=new URI(publicBaseUrl);if(!base.isAbsolute()||base.isOpaque()||base.getHost()==null||base.getRawQuery()!=null||base.getRawFragment()!=null||base.getUserInfo()!=null
                ||!("http".equalsIgnoreCase(base.getScheme())||"https".equalsIgnoreCase(base.getScheme())))throw new IllegalArgumentException();
            String value=base.toASCIIString();while(value.endsWith("/"))value=value.substring(0,value.length()-1);
            return new URI(value+"/resources/"+ns+"/"+resource).toASCIIString();
        }catch(URISyntaxException|RuntimeException e){throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid configured resource URL");}
    }
    public static String markdown(String publicBaseUrl,String namespaceId,String resourceId,String storedFilename){
        String label=storedFilename.replace("\\","\\\\").replace("[","\\[").replace("]","\\]");
        String target=url(publicBaseUrl,namespaceId,resourceId).replace("(","%28").replace(")","%29");
        return "["+label+"]("+target+")";
    }
}
