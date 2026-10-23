package online.yudream.base.plugin.cas.infrastructure.cas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CasServiceResponseParserTest {

    @Test
    void parsesSuccessWithAttributes() {
        String xml = """
                <cas:serviceResponse xmlns:cas="http://www.yale.edu/tp/cas">
                  <cas:authenticationSuccess>
                    <cas:user>20210001</cas:user>
                    <cas:attributes>
                      <cas:displayName>张三</cas:displayName>
                      <cas:cn>zhangsan</cas:cn>
                    </cas:attributes>
                  </cas:authenticationSuccess>
                </cas:serviceResponse>
                """;
        CasServiceResponseParser.CasIdentity identity = CasServiceResponseParser.parse(xml);
        assertEquals("20210001", identity.user());
        assertEquals("张三", identity.displayName());
    }

    @Test
    void parsesUnprefixedSuccess() {
        String xml = """
                <serviceResponse>
                  <authenticationSuccess>
                    <user>alice</user>
                  </authenticationSuccess>
                </serviceResponse>
                """;
        CasServiceResponseParser.CasIdentity identity = CasServiceResponseParser.parse(xml);
        assertEquals("alice", identity.user());
        assertEquals("alice", identity.displayName());
    }

    @Test
    void rejectsFailure() {
        String xml = """
                <cas:serviceResponse xmlns:cas="http://www.yale.edu/tp/cas">
                  <cas:authenticationFailure code="INVALID_TICKET">Ticket ST-1 not recognized</cas:authenticationFailure>
                </cas:serviceResponse>
                """;
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> CasServiceResponseParser.parse(xml));
        assertTrue(error.getMessage().contains("INVALID_TICKET"));
        assertTrue(error.getMessage().contains("not recognized"));
    }

    @Test
    void rejectsBlank() {
        assertThrows(IllegalStateException.class, () -> CasServiceResponseParser.parse(""));
    }

    @Test
    void rejectsDoctype() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <cas:serviceResponse xmlns:cas="http://www.yale.edu/tp/cas">
                  <cas:authenticationSuccess>
                    <cas:user>&xxe;</cas:user>
                  </cas:authenticationSuccess>
                </cas:serviceResponse>
                """;
        assertThrows(IllegalStateException.class, () -> CasServiceResponseParser.parse(xml));
    }
}
