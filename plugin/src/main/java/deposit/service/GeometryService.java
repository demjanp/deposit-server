package deposit.service;
import deposit.common.*;
import java.util.*;
public final class GeometryService extends BaseService {
    public GeometryService(RequestContext c){super(c);}
    public Map<String,Object> convert(Map<String,Object> request,boolean coordinates) {
        readContext();authenticate();c.revision=c.server.revision(c.state);
        if(coordinates) {fields(request,"type","coordinates","srid","verticalSrid");return GeometryCodec.fromCoordinates(request.get("type"),request.get("coordinates"),request.get("srid"),request.get("verticalSrid"));}
        fields(request,"wkt","srid","verticalSrid");return GeometryCodec.fromWkt(request.get("wkt"),request.get("srid"),request.get("verticalSrid"));
    }
}
