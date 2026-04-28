package me.cerial.nova.cloudcombat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class CombatCloudProtocol {
    static final int VERSION = 3;
    static final byte FRAME_CLIENT_HELLO = 1;
    static final byte FRAME_SERVER_CHALLENGE = 2;
    static final byte FRAME_CLIENT_AUTH = 3;
    static final byte FRAME_SERVER_ACCEPT = 4;
    static final byte FRAME_REJECT = 5;
    static final byte FRAME_ENCRYPTED = 6;

    private static final int MAGIC = 0x49434332;
    private static final int MAX_FRAME_LENGTH = 1 << 20;
    private static final String AUTH_LABEL = "nova-combat-cloud-auth-v3";
    private static final String SERVER_PROOF_LABEL = "nova-combat-cloud-server-v3";
    private static final String KEY_LABEL = "nova-combat-cloud-key-v3";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final byte VALUE_STRING = 1;
    private static final byte VALUE_INT = 2;
    private static final byte VALUE_LONG = 3;
    private static final byte VALUE_DOUBLE = 4;
    private static final byte VALUE_BOOLEAN = 5;
    private static final byte VALUE_STRING_ARRAY = 6;

    private CombatCloudProtocol() {
    }

    static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    static void writeFrame(DataOutputStream output, byte type, byte[] payload) throws IOException {
        byte[] body = payload == null ? new byte[0] : payload;
        output.writeInt(MAGIC);
        output.writeByte(type);
        output.writeInt(body.length);
        output.write(body);
        output.flush();
    }

    static Frame readFrame(DataInputStream input) throws IOException {
        int magic = input.readInt();
        if (magic != MAGIC) {
            throw new IOException("Invalid combat cloud protocol magic");
        }
        byte type = input.readByte();
        int length = input.readInt();
        if (length < 0 || length > MAX_FRAME_LENGTH) {
            throw new IOException("Invalid combat cloud frame length " + length);
        }
        byte[] payload = new byte[length];
        input.readFully(payload);
        return new Frame(type, payload);
    }

    static byte[] clientHelloPayload(UUID serverId, String licenseKey, String username, byte[] clientNonce) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(VERSION);
        output.writeUTF(serverId == null ? "" : serverId.toString());
        output.writeUTF(licenseKey == null ? "" : licenseKey);
        output.writeUTF(username == null ? "" : username);
        writeBytes(output, clientNonce);
        output.flush();
        return bytes.toByteArray();
    }

    static ClientHello readClientHello(byte[] payload) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
        int protocol = input.readInt();
        UUID serverId = UUID.fromString(input.readUTF());
        String licenseKey = input.readUTF();
        String username = input.readUTF();
        byte[] clientNonce = readBytes(input);
        if (clientNonce.length < 16) {
            throw new IOException("Combat cloud client nonce too short");
        }
        return new ClientHello(protocol, serverId, licenseKey, username, clientNonce);
    }

    static byte[] bytesPayload(byte[] bytes) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(payload);
        writeBytes(output, bytes);
        output.flush();
        return payload.toByteArray();
    }

    static byte[] readBytesPayload(byte[] payload) throws IOException {
        return readBytes(new DataInputStream(new ByteArrayInputStream(payload)));
    }

    static byte[] serverAcceptPayload(String engine, byte[] serverProof) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(payload);
        output.writeInt(VERSION);
        output.writeUTF(engine == null ? "" : engine);
        writeBytes(output, serverProof);
        output.flush();
        return payload.toByteArray();
    }

    static ServerAccept readServerAccept(byte[] payload) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
        return new ServerAccept(input.readInt(), input.readUTF(), readBytes(input));
    }

    static byte[] authentication(String sessionToken,
                                 String licenseKey,
                                 String username,
                                 UUID serverId,
                                 byte[] clientNonce,
                                 byte[] serverNonce) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenBytes(sessionToken), "HmacSHA256"));
            mac.update(AUTH_LABEL.getBytes(StandardCharsets.UTF_8));
            mac.update(canonicalLicenseKey(licenseKey).getBytes(StandardCharsets.UTF_8));
            mac.update(canonicalUsername(username).getBytes(StandardCharsets.UTF_8));
            mac.update((serverId == null ? "" : serverId.toString()).getBytes(StandardCharsets.UTF_8));
            mac.update(clientNonce);
            mac.update(serverNonce);
            return mac.doFinal();
        } catch (GeneralSecurityException exception) {
            throw new IOException("Unable to authenticate combat cloud session", exception);
        }
    }

    static boolean secureEquals(byte[] left, byte[] right) {
        return MessageDigest.isEqual(left, right);
    }

    static byte[] serverProof(String sessionToken,
                              String licenseKey,
                              String username,
                              UUID serverId,
                              byte[] clientNonce,
                              byte[] serverNonce) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenBytes(sessionToken), "HmacSHA256"));
            mac.update(SERVER_PROOF_LABEL.getBytes(StandardCharsets.UTF_8));
            mac.update(canonicalLicenseKey(licenseKey).getBytes(StandardCharsets.UTF_8));
            mac.update(canonicalUsername(username).getBytes(StandardCharsets.UTF_8));
            mac.update((serverId == null ? "" : serverId.toString()).getBytes(StandardCharsets.UTF_8));
            mac.update(clientNonce);
            mac.update(serverNonce);
            return mac.doFinal();
        } catch (GeneralSecurityException exception) {
            throw new IOException("Unable to verify combat cloud server", exception);
        }
    }

    static byte[] sessionKey(String sessionToken,
                             String licenseKey,
                             String username,
                             UUID serverId,
                             byte[] clientNonce,
                             byte[] serverNonce) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(KEY_LABEL.getBytes(StandardCharsets.UTF_8));
            digest.update(tokenBytes(sessionToken));
            digest.update(canonicalLicenseKey(licenseKey).getBytes(StandardCharsets.UTF_8));
            digest.update(canonicalUsername(username).getBytes(StandardCharsets.UTF_8));
            digest.update((serverId == null ? "" : serverId.toString()).getBytes(StandardCharsets.UTF_8));
            digest.update(clientNonce);
            digest.update(serverNonce);
            return digest.digest();
        } catch (GeneralSecurityException exception) {
            throw new IOException("Unable to derive combat cloud session key", exception);
        }
    }

    static void writeEncryptedObject(DataOutputStream output, byte[] key, JsonObject object) throws IOException {
        writeFrame(output, FRAME_ENCRYPTED, encrypt(key, encodeObject(object)));
    }

    static JsonObject readEncryptedObject(DataInputStream input, byte[] key) throws IOException {
        Frame frame = readFrame(input);
        if (frame.type == FRAME_REJECT) {
            throw new IOException(readReject(frame.payload));
        }
        if (frame.type != FRAME_ENCRYPTED) {
            throw new IOException("Unexpected combat cloud frame " + frame.type);
        }
        return decodeObject(decrypt(key, frame.payload));
    }

    static byte[] rejectPayload(String reason) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(payload);
        output.writeUTF(reason == null ? "" : reason);
        output.flush();
        return payload.toByteArray();
    }

    static String readReject(byte[] payload) throws IOException {
        return new DataInputStream(new ByteArrayInputStream(payload)).readUTF();
    }

    private static byte[] encodeObject(JsonObject object) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(object == null ? 0 : object.entrySet().size());
        if (object != null) {
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                writeValue(output, entry.getKey(), entry.getValue());
            }
        }
        output.flush();
        return bytes.toByteArray();
    }

    private static JsonObject decodeObject(byte[] bytes) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
        JsonObject object = new JsonObject();
        int fields = input.readInt();
        if (fields < 0 || fields > 256) {
            throw new IOException("Invalid combat cloud object field count " + fields);
        }
        for (int i = 0; i < fields; i++) {
            String key = input.readUTF();
            byte type = input.readByte();
            switch (type) {
                case VALUE_STRING:
                    object.addProperty(key, input.readUTF());
                    break;
                case VALUE_INT:
                    object.addProperty(key, input.readInt());
                    break;
                case VALUE_LONG:
                    object.addProperty(key, input.readLong());
                    break;
                case VALUE_DOUBLE:
                    object.addProperty(key, input.readDouble());
                    break;
                case VALUE_BOOLEAN:
                    object.addProperty(key, input.readBoolean());
                    break;
                case VALUE_STRING_ARRAY:
                    JsonArray array = new JsonArray();
                    int length = input.readInt();
                    if (length < 0 || length > 512) {
                        throw new IOException("Invalid combat cloud array length " + length);
                    }
                    for (int element = 0; element < length; element++) {
                        array.add(new JsonPrimitive(input.readUTF()));
                    }
                    object.add(key, array);
                    break;
                default:
                    throw new IOException("Invalid combat cloud value type " + type);
            }
        }
        return object;
    }

    private static void writeValue(DataOutputStream output, String key, JsonElement element) throws IOException {
        output.writeUTF(key == null ? "" : key);
        if (element == null || element.isJsonNull()) {
            output.writeByte(VALUE_STRING);
            output.writeUTF("");
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            output.writeByte(VALUE_STRING_ARRAY);
            output.writeInt(array.size());
            for (JsonElement value : array) {
                output.writeUTF(value == null || value.isJsonNull() ? "" : value.getAsString());
            }
            return;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            output.writeByte(VALUE_BOOLEAN);
            output.writeBoolean(primitive.getAsBoolean());
            return;
        }
        if (primitive.isNumber()) {
            String raw = primitive.getAsString();
            if (raw.indexOf('.') >= 0 || raw.indexOf('E') >= 0 || raw.indexOf('e') >= 0) {
                output.writeByte(VALUE_DOUBLE);
                output.writeDouble(primitive.getAsDouble());
                return;
            }
            try {
                long value = Long.parseLong(raw);
                if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                    output.writeByte(VALUE_INT);
                    output.writeInt((int) value);
                } else {
                    output.writeByte(VALUE_LONG);
                    output.writeLong(value);
                }
                return;
            } catch (NumberFormatException ignored) {
                output.writeByte(VALUE_DOUBLE);
                output.writeDouble(primitive.getAsDouble());
                return;
            }
        }
        output.writeByte(VALUE_STRING);
        output.writeUTF(primitive.getAsString());
    }

    private static byte[] encrypt(byte[] key, byte[] plaintext) throws IOException {
        try {
            byte[] nonce = randomBytes(12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(normalizedKey(key), "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] output = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, output, nonce.length, encrypted.length);
            return output;
        } catch (GeneralSecurityException exception) {
            throw new IOException("Unable to encrypt combat cloud frame", exception);
        }
    }

    private static byte[] decrypt(byte[] key, byte[] payload) throws IOException {
        if (payload.length < 13) {
            throw new EOFException("Encrypted combat cloud payload too short");
        }
        try {
            byte[] nonce = Arrays.copyOfRange(payload, 0, 12);
            byte[] encrypted = Arrays.copyOfRange(payload, 12, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(normalizedKey(key), "AES"), new GCMParameterSpec(128, nonce));
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Unable to decrypt combat cloud frame", exception);
        }
    }

    private static byte[] normalizedKey(byte[] key) {
        return key == null || key.length != 32 ? Arrays.copyOf(key == null ? new byte[0] : key, 32) : key;
    }

    private static byte[] tokenBytes(String sessionToken) {
        return (sessionToken == null ? "" : sessionToken).getBytes(StandardCharsets.UTF_8);
    }

    private static String canonicalLicenseKey(String licenseKey) {
        return licenseKey == null ? "" : licenseKey.trim().toUpperCase(Locale.ROOT);
    }

    private static String canonicalUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
        byte[] value = bytes == null ? new byte[0] : bytes;
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 4096) {
            throw new IOException("Invalid combat cloud byte array length " + length);
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    static final class Frame {
        final byte type;
        final byte[] payload;

        Frame(byte type, byte[] payload) {
            this.type = type;
            this.payload = payload == null ? new byte[0] : payload;
        }
    }

    static final class ClientHello {
        final int protocol;
        final UUID serverId;
        final String licenseKey;
        final String username;
        final byte[] clientNonce;

        ClientHello(int protocol, UUID serverId, String licenseKey, String username, byte[] clientNonce) {
            this.protocol = protocol;
            this.serverId = serverId;
            this.licenseKey = licenseKey == null ? "" : licenseKey;
            this.username = username == null ? "" : username;
            this.clientNonce = clientNonce;
        }
    }

    static final class ServerAccept {
        final int protocol;
        final String engine;
        final byte[] serverProof;

        ServerAccept(int protocol, String engine, byte[] serverProof) {
            this.protocol = protocol;
            this.engine = engine;
            this.serverProof = serverProof == null ? new byte[0] : serverProof;
        }
    }
}
