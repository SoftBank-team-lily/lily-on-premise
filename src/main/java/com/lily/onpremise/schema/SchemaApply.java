package com.lily.onpremise.schema;

import java.util.Map;

/**
 * 후보 컨테이너를 띄우기 전에 앱 DB에 스키마를 적용한다.
 * 스크립트가 없으면 호출하지 않는다.
 */
@FunctionalInterface
public interface SchemaApply {

    SchemaApply NONE = (env, scripts) -> { };

    void apply(Map<String, String> env, Map<String, String> scripts);
}
