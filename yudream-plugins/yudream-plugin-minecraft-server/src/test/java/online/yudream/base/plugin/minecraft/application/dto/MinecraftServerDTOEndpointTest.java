package online.yudream.base.plugin.minecraft.application.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinecraftServerDTOEndpointTest {

    @Test
    void automaticPortEndpointOmitsPortSuffix() {
        MinecraftServerDTO.EndpointDTO endpoint = endpoint("play.example.com", 0);
        assertEquals("play.example.com", endpoint.address());
    }

    @Test
    void defaultJavaPortOmitsPortSuffix() {
        MinecraftServerDTO.EndpointDTO endpoint = endpoint("play.example.com", 25565);
        assertEquals("play.example.com", endpoint.address());
    }

    @Test
    void customPortKeepsPortSuffix() {
        MinecraftServerDTO.EndpointDTO endpoint = endpoint("play.example.com", 25566);
        assertEquals("play.example.com:25566", endpoint.address());
    }

    @Test
    void bedrockDefaultPortKeepsPortSuffix() {
        MinecraftServerDTO.EndpointDTO endpoint = new MinecraftServerDTO.EndpointDTO(
                "ep-1", "基岩线路", "mc.example.com", 19132, "BEDROCK", true, true, 0);
        assertEquals("mc.example.com:19132", endpoint.address());
    }

    private static MinecraftServerDTO.EndpointDTO endpoint(String host, int port) {
        return new MinecraftServerDTO.EndpointDTO("ep-1", "默认线路", host, port, "JAVA", true, true, 0);
    }
}
