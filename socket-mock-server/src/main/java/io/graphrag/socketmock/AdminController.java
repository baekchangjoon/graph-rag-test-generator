package io.graphrag.socketmock;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
public class AdminController {

    public record ExpectationRequest(int listenPort, String onReceiveHex,
                                     String respondWithHex, MatchMode matchMode) {
    }

    private final ExpectationRegistry registry;
    private final TcpListenerManager listeners;

    public AdminController(ExpectationRegistry registry, TcpListenerManager listeners) {
        this.registry = registry;
        this.listeners = listeners;
    }

    @PostMapping("/__admin/expectations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> register(@RequestBody ExpectationRequest request) {
        try {
            listeners.ensureListening(request.listenPort());
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        } catch (RuntimeException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, "bind failed for port " + request.listenPort());
        }
        String id = UUID.randomUUID().toString();
        registry.register(new Expectation(id, request.listenPort(), request.onReceiveHex(),
                request.respondWithHex(),
                request.matchMode() == null ? MatchMode.EXACT : request.matchMode()));
        return Map.of("id", id);
    }

    @DeleteMapping("/__admin/expectations")
    public void clear() {
        registry.clear();
    }
}
