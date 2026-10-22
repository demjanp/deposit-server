package org.deposit.it;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.TransactionConfig;

/** Per-call test fixture construction shared by matching integration fixtures. */
final class TestTransport {
    private TestTransport() {}

    static Map<String,Object> orderedMap(Object... entries) {
        var result = new LinkedHashMap<String,Object>();
        for (int i=0; i<entries.length; i+=2) result.put((String)entries[i], entries[i+1]);
        return result;
    }

    static PositionalCall positionalCall(String procedure, Object... arguments) {
        var parameters = new LinkedHashMap<String,Object>();
        var names = new ArrayList<String>();
        for (int i=0; i<arguments.length; i++) {
            String name = "p" + i;
            parameters.put(name, arguments[i]);
            names.add("$" + name);
        }
        return new PositionalCall("CALL " + procedure + "(" + String.join(",", names) + ")", parameters);
    }

    static Record call(Driver driver, Duration timeout, String procedure, Object... arguments) {
        PositionalCall call = positionalCall(procedure, arguments);
        try (var session = driver.session()) {
            return session.run("CYPHER 25 " + call.text(), call.parameters(),
                TransactionConfig.builder().withTimeout(timeout).build()).list().getFirst();
        }
    }

    static HttpClient cookieClient() {
        return HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    static URI endpoint(URI base, String path) {
        return URI.create(base.toASCIIString() + path);
    }

    static String origin(URI base) {
        int port = base.getPort(), defaultPort = "https".equals(base.getScheme()) ? 443 : 80;
        return base.getScheme() + "://" + base.getHost() + (port < 0 || port == defaultPort ? "" : ":" + port);
    }

    record PositionalCall(String text, Map<String,Object> parameters) {}
}
