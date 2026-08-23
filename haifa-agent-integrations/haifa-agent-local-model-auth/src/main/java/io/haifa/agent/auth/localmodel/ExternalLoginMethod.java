package io.haifa.agent.auth.localmodel;

import java.time.Instant;

/** Allowlisted external login driver. It does not own product UI, model calls, Runtime, or fallback. */
public interface ExternalLoginMethod {
    ExternalLoginMethodDescriptor descriptor();

    ExternalLoginOperation create(ExternalLoginMode mode, ExternalLoginOperationContext context);

    StoredExternalCredential refresh(StoredExternalCredential credential, Instant refreshBefore);

    void revoke(StoredExternalCredential credential);
}
