package online.yudream.base.plugin.wordle.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.wordle.bootstrap.WordlePlugin;
import online.yudream.base.plugin.wordle.interfaces.http.WordleHttpFacade;

public class WordleUserController {

    private final WordleHttpFacade http;

    public WordleUserController(WordleHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/stats", permission = WordlePlugin.USE_PERMISSION)
    public PluginHttpResponse myStats(PluginHttpRequest request) {
        return http.myStats(request);
    }
}
