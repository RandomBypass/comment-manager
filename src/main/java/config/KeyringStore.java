package config;

import com.github.javakeyring.BackendNotSupportedException;
import com.github.javakeyring.Keyring;
import com.github.javakeyring.PasswordAccessException;

/**
 * Stores and retrieves the OAuth2 client_secrets.json content using the OS keyring
 * (Windows Credential Manager, macOS Keychain, or Linux Secret Service).
 *
 * <p>The JSON content — not a file path — is stored so that the original file is
 * no longer needed after the first login.</p>
 */
public class KeyringStore {

    private static final String SERVICE = "youtube-comment-manager";
    private static final String ACCOUNT = "client-secrets";

    /**
     * Saves the client_secrets.json content to the OS keyring.
     *
     * @throws BackendNotSupportedException if the keyring backend is unavailable
     * @throws PasswordAccessException      if the write fails
     */
    public void save(String jsonContent) throws Exception {
        try (Keyring keyring = Keyring.create()) {
            keyring.setPassword(SERVICE, ACCOUNT, jsonContent);
        }
    }

    /**
     * Loads the client_secrets.json content from the OS keyring.
     *
     * @return the stored JSON string, or {@code null} if not present or keyring is unavailable
     */
    public String load() {
        try (Keyring keyring = Keyring.create()) {
            return keyring.getPassword(SERVICE, ACCOUNT);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Removes the stored client secrets from the keyring (e.g. on full account disconnect).
     * Failures are silently ignored.
     */
    public void clear() {
        try (Keyring keyring = Keyring.create()) {
            keyring.deletePassword(SERVICE, ACCOUNT);
        } catch (Exception ignored) {
        }
    }
}