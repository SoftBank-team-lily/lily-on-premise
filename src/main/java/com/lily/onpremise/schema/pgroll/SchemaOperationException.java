package com.lily.onpremise.schema.pgroll;

/** pgroll 명령이나 상태 조회가 실패했다 */
public class SchemaOperationException extends RuntimeException {

    public SchemaOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
