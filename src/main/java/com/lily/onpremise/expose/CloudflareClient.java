package com.lily.onpremise.expose;

import com.fasterxml.jackson.databind.JsonNode;

/** Cloudflare API v4. 성공 응답의 {@code result} 만 돌려준다. */
public interface CloudflareClient {

    JsonNode call(String method, String path, JsonNode body);
}
