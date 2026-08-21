package online.yudream.base.plugin.wordle.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.wordle.bootstrap.WordlePlugin;
import online.yudream.base.plugin.wordle.interfaces.http.WordleHttpFacade;

public class WordleAdminController {

    private final WordleHttpFacade http;

    public WordleAdminController(WordleHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/overview", permission = WordlePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse overview(PluginHttpRequest request) {
        return http.overview();
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/words", permission = WordlePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse words(PluginHttpRequest request) {
        return http.words(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/words", permission = WordlePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse createWord(PluginHttpRequest request) {
        return http.createWord(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/words/{id}", permission = WordlePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse updateWord(PluginHttpRequest request) {
        return http.updateWord(request);
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/words/{id}", permission = WordlePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deleteWord(PluginHttpRequest request) {
        return http.deleteWord(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/games", permission = WordlePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse games(PluginHttpRequest request) {
        return http.games(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/players", permission = WordlePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse players(PluginHttpRequest request) {
        return http.players(request);
    }
}
