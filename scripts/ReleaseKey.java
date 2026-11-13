import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Base64;

/** Export the update public key or sign an update manifest with the APK signing key. */
public class ReleaseKey {
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || !(args[0].equals("export") || args[0].equals("sign"))) {
            throw new IllegalArgumentException("Usage: ReleaseKey export <keystore> <public.pem> | sign <keystore> <public.pem> <manifest> <signature>");
        }
        if (args[0].equals("sign") && args.length != 5 || args[0].equals("export") && args.length != 3) {
            throw new IllegalArgumentException("Incorrect number of arguments");
        }
        String configured = System.getenv("KEYSTORE_PASSWORD");
        Console console = System.console();
        char[] password = configured != null ? configured.toCharArray() : console != null ? console.readPassword("密钥库口令: ") : null;
        if (password == null || password.length == 0) throw new IllegalStateException("Keystore password required");
        try {
            KeyStore store = KeyStore.getInstance(Path.of(args[1]).toFile(), password);
            Certificate certificate = store.getCertificate("lilt");
            if (certificate == null) throw new IllegalStateException("Keystore does not contain alias lilt");
            byte[] publicKey = certificate.getPublicKey().getEncoded();
            if (args[0].equals("export")) {
                String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(publicKey) + "\n-----END PUBLIC KEY-----\n";
                Files.writeString(Path.of(args[2]), pem, StandardCharsets.US_ASCII);
            } else {
                String pem = Files.readString(Path.of(args[2]), StandardCharsets.US_ASCII)
                    .replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
                if (!Arrays.equals(publicKey, Base64.getDecoder().decode(pem))) throw new IllegalStateException("Configured update public key does not match APK signing key");
                PrivateKey key = (PrivateKey) store.getKey("lilt", password);
                Signature signature = Signature.getInstance("SHA256withRSA");
                signature.initSign(key);
                signature.update(Files.readAllBytes(Path.of(args[3])));
                Files.write(Path.of(args[4]), signature.sign());
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
