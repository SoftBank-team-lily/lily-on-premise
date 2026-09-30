package com.lily.onpremise.expose;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HTTP/1.x 요청·응답의 시작 줄과 헤더. 프록시는 헤더를 고치지 않고 그대로 넘기고,
 * 본문 경계(Content-Length / chunked)와 keep-alive 여부만 여기서 읽는다.
 */
record HttpHead(String startLine, List<String> headers) {

    private static final int MAX_LINE = 16 * 1024;
    private static final int MAX_HEADERS = 200;

    /** 연결이 요청 사이에서 끝났으면 null */
    static HttpHead read(InputStream in) throws IOException {
        String start = readLine(in);
        while (start != null && start.isEmpty()) {
            start = readLine(in);
        }
        if (start == null) {
            return null;
        }
        List<String> headers = new ArrayList<>();
        while (true) {
            String line = readLine(in);
            if (line == null) {
                throw new EOFException("헤더 도중 연결 종료");
            }
            if (line.isEmpty()) {
                return new HttpHead(start, headers);
            }
            if (headers.size() >= MAX_HEADERS) {
                throw new IOException("헤더가 너무 많습니다");
            }
            headers.add(line);
        }
    }

    /** CRLF 또는 LF 까지 읽는다. 첫 바이트에서 EOF 면 null */
    static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (line.isEmpty()) {
                    return null;
                }
                throw new EOFException("줄 도중 연결 종료");
            }
            if (b == '\n') {
                int end = line.length();
                if (end > 0 && line.charAt(end - 1) == '\r') {
                    line.setLength(end - 1);
                }
                return line.toString();
            }
            if (line.length() >= MAX_LINE) {
                throw new IOException("줄이 너무 깁니다");
            }
            line.append((char) b);
        }
    }

    void writeTo(OutputStream out) throws IOException {
        StringBuilder head = new StringBuilder(startLine).append("\r\n");
        for (String header : headers) {
            head.append(header).append("\r\n");
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    String header(String name) {
        for (String header : headers) {
            int colon = header.indexOf(':');
            if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return header.substring(colon + 1).trim();
            }
        }
        return null;
    }

    String method() {
        int space = startLine.indexOf(' ');
        return space < 0 ? startLine : startLine.substring(0, space);
    }

    /** 응답의 상태 코드. 읽을 수 없으면 0 */
    int status() {
        String[] parts = startLine.split(" ", 3);
        try {
            return parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    boolean chunked() {
        String encoding = header("Transfer-Encoding");
        return encoding != null && encoding.toLowerCase(Locale.ROOT).contains("chunked");
    }

    /** 없거나 읽을 수 없으면 -1 */
    long contentLength() {
        String length = header("Content-Length");
        if (length == null) {
            return -1;
        }
        try {
            return Long.parseLong(length);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 요청 기준: 본문이 있는가 (HTTP/1.1 요청은 길이 헤더가 없으면 본문이 없다) */
    boolean hasRequestBody() {
        return chunked() || contentLength() > 0;
    }

    /** 요청이면 startLine 끝, 응답이면 처음이 버전이다 */
    boolean keepAlive(boolean response) {
        String version = response ? startLine.split(" ", 2)[0] : startLine.substring(startLine.lastIndexOf(' ') + 1);
        String connection = header("Connection");
        String value = connection == null ? "" : connection.toLowerCase(Locale.ROOT);
        if (version.equalsIgnoreCase("HTTP/1.0")) {
            return value.contains("keep-alive");
        }
        return !value.contains("close");
    }
}
