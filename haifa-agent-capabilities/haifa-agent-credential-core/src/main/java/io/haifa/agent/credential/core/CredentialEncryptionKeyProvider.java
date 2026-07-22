package io.haifa.agent.credential.core;

import javax.crypto.SecretKey;

@FunctionalInterface
public interface CredentialEncryptionKeyProvider {
    SecretKey keyForEncryption();
}
