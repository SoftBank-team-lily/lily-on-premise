package com.lily.onpremise.burst;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 클라우드 Ingress 가 공개 호스트의 요청을 Pod 까지 보내는지 확인한다.
 * Host 헤더를 공개 주소로 넣어야 하므로 java.net.http 대신 소켓으로 보낸다.
 */
final class IngressProbe {

    private static final int TIMEOUT_MILLIS = 2_000;

    private IngressProbe() {
    }

    /** 응답이 502/503/504 가 아니면 Pod 가 받은 것이다. 연결 실패도 false */
    static boolean reachesPod(InetSocketAddress ingress, String host) {
        try (Socket socket = new Socket()) {
            socket.connect(ingress, TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(("HEAD / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            String status = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
                    .readLine();
            if (status == null) {
                return false;
            }
            String[] parts = status.split(" ", 3);
            int code = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return code > 0 && code != 502 && code != 503 && code != 504;
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }
}
