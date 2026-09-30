package com.lily.onpremise.expose;

/** 사용자에게 돌려줄 공개 주소. API 가 설정된 경우에는 이 호출이 호스트이름과 인증서를 맞춘다. */
public interface PublicAddress {

    String ensure(String appName);
}
