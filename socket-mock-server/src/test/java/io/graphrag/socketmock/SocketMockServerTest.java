package io.graphrag.socketmock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SocketMockServerTest {

    @Autowired
    TestRestTemplate rest;

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void registeredExpectation_servesTcpBytes() throws Exception {
        int tcpPort = freePort();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"listenPort":%d,"onReceiveHex":"01 02 03","respondWithHex":"02 00","matchMode":"EXACT"}"""
                .formatted(tcpPort);
        ResponseEntity<String> registered =
                rest.postForEntity("/__admin/expectations", new HttpEntity<>(body, headers), String.class);
        assertThat(registered.getStatusCode().value()).isEqualTo(201);

        try (Socket socket = new Socket("localhost", tcpPort)) {
            OutputStream out = socket.getOutputStream();
            out.write(new byte[]{0x01, 0x02, 0x03});
            out.flush();

            InputStream in = socket.getInputStream();
            byte[] response = in.readNBytes(2);
            assertThat(response).containsExactly(0x02, 0x00);
        }

        rest.delete("/__admin/expectations");
    }
}
