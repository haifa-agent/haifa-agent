package io.haifa.agent.model.api;

/** Supported non-secret provider connection methods declared by a static model catalog. */
public enum ModelAuthenticationMethod {
    API_KEY,
    EXTERNAL_LOGIN,
    ENVIRONMENT,
    HOST_MANAGED
}
