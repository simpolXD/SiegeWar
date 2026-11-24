/*
 * Decompiled with CFR 0.152.
 */
package com.abyssaldominion.siegewar.runtime.recovery;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class SnapshotCodec {
    public static final int MAX_BYTES = 0x200000;

    private SnapshotCodec() {}

    public static byte[] encode(PlayerSnapshot s) throws IOException {
        SnapshotCodec.validate(s);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream d = new DataOutputStream(buffer); ) {
            d.writeInt(1095980849);
            d.writeInt(1);
            SnapshotCodec.uuid(d, s.player());
            SnapshotCodec.uuid(d, s.session());
            SnapshotCodec.uuid(d, s.world());
            d.writeUTF(s.worldName());
            d.writeDouble(s.x());
            d.writeDouble(s.y());
            d.writeDouble(s.z());
            d.writeFloat(s.yaw());
            d.writeFloat(s.pitch());
            SnapshotCodec.blob(d, s.inventory());
            SnapshotCodec.blob(d, s.cursor());
            d.writeInt(s.heldSlot());
            d.writeUTF(s.gameMode());
            d.writeInt(s.level());
            d.writeFloat(s.exp());
            d.writeInt(s.totalExp());
            d.writeDouble(s.health());
            d.writeDouble(s.absorption());
            d.writeInt(s.food());
            d.writeFloat(s.saturation());
            d.writeFloat(s.exhaustion());
            d.writeBoolean(s.allowFlight());
            d.writeBoolean(s.flying());
            d.writeFloat(s.flySpeed());
            d.writeFloat(s.walkSpeed());
            d.writeInt(s.fireTicks());
            d.writeInt(s.freezeTicks());
            d.writeInt(s.remainingAir());
            d.writeFloat(s.fallDistance());
            d.writeBoolean(s.invulnerable());
            d.writeBoolean(s.collidable());
            d.writeInt(s.effects().size());
            for (PlayerSnapshot.Effect e : s.effects()) {
                d.writeUTF(e.type());
                d.writeInt(e.ticks());
                d.writeInt(e.amplifier());
                d.writeBoolean(e.ambient());
                d.writeBoolean(e.particles());
                d.writeBoolean(e.icon());
            }
        }
        byte[] payload = buffer.toByteArray();
        if (payload.length > 2097120) {
            throw new IOException("Escrow exceeds byte budget");
        }
        buffer.write(SnapshotCodec.digest(payload));
        return buffer.toByteArray();
    }

    public static PlayerSnapshot decode(byte[] bytes) throws IOException {
        PlayerSnapshot playerSnapshot;
        if (bytes == null || bytes.length < 40 || bytes.length > 0x200000) {
            throw new IOException("Invalid escrow size");
        }
        byte[] payload = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(
                SnapshotCodec.digest(payload),
                Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length))) {
            throw new IOException("Escrow integrity check failed");
        }
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            if (d.readInt() != 1095980849 || d.readInt() != 1) {
                throw new IOException("Unsupported escrow schema");
            }
            UUID player = SnapshotCodec.uuid(d);
            UUID session = SnapshotCodec.uuid(d);
            UUID world = SnapshotCodec.uuid(d);
            String name = d.readUTF();
            double x = d.readDouble();
            double y = d.readDouble();
            double z = d.readDouble();
            float yaw = d.readFloat();
            float pitch = d.readFloat();
            byte[] inv = SnapshotCodec.blob(d);
            byte[] cursor = SnapshotCodec.blob(d);
            int slot = d.readInt();
            String mode = d.readUTF();
            int level = d.readInt();
            float exp = d.readFloat();
            int total = d.readInt();
            double hp = d.readDouble();
            double abs = d.readDouble();
            int food = d.readInt();
            float saturation = d.readFloat();
            float exhaustion = d.readFloat();
            boolean allow = d.readBoolean();
            boolean flying = d.readBoolean();
            float flySpeed = d.readFloat();
            float walkSpeed = d.readFloat();
            int fire = d.readInt();
            int freeze = d.readInt();
            int air = d.readInt();
            float fall = d.readFloat();
            boolean invulnerable = d.readBoolean();
            boolean collidable = d.readBoolean();
            int count = d.readInt();
            ArrayList<PlayerSnapshot.Effect> effects = new ArrayList<PlayerSnapshot.Effect>();
            if (count < 0 || count > 128) {
                throw new IOException("Invalid effect count");
            }
            for (int i = 0; i < count; ++i) {
                effects.add(
                        new PlayerSnapshot.Effect(
                                d.readUTF(),
                                d.readInt(),
                                d.readInt(),
                                d.readBoolean(),
                                d.readBoolean(),
                                d.readBoolean()));
            }
            if (d.available() != 0) {
                throw new IOException("Unexpected escrow fields");
            }
            PlayerSnapshot snapshot =
                    new PlayerSnapshot(
                            player,
                            session,
                            world,
                            name,
                            x,
                            y,
                            z,
                            yaw,
                            pitch,
                            inv,
                            cursor,
                            slot,
                            mode,
                            level,
                            exp,
                            total,
                            hp,
                            abs,
                            food,
                            saturation,
                            exhaustion,
                            allow,
                            flying,
                            flySpeed,
                            walkSpeed,
                            fire,
                            freeze,
                            air,
                            fall,
                            invulnerable,
                            collidable,
                            effects);
            SnapshotCodec.validate(snapshot);
            playerSnapshot = snapshot;
        } catch (Throwable throwable) {
            try {
                try {
                    d.close();
                } catch (Throwable throwable2) {
                    throwable.addSuppressed(throwable2);
                }
                throw throwable;
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid escrow values", e);
            }
        }
        d.close();
        return playerSnapshot;
    }

    private static void validate(PlayerSnapshot s) throws IOException {
        if (s == null
                || s.worldName().isBlank()
                || s.worldName().length() > 256
                || !Set.of("SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR").contains(s.gameMode())
                || s.heldSlot() < 0
                || s.heldSlot() > 8
                || s.level() < 0
                || s.totalExp() < 0
                || !SnapshotCodec.finite(
                        s.x(),
                        s.y(),
                        s.z(),
                        s.yaw(),
                        s.pitch(),
                        s.health(),
                        s.absorption(),
                        s.exp(),
                        s.saturation(),
                        s.exhaustion(),
                        s.flySpeed(),
                        s.walkSpeed(),
                        s.fallDistance())
                || Math.abs(s.x()) > 3.0E7
                || Math.abs(s.z()) > 3.0E7
                || Math.abs(s.y()) > 3.0E7
                || Math.abs(s.pitch()) > 90.0f
                || s.health() <= 0.0
                || s.absorption() < 0.0
                || s.exp() < 0.0f
                || s.exp() > 1.0f
                || s.food() < 0
                || s.food() > 20
                || s.saturation() < 0.0f
                || s.exhaustion() < 0.0f
                || Math.abs(s.flySpeed()) > 1.0f
                || Math.abs(s.walkSpeed()) > 1.0f
                || s.freezeTicks() < 0
                || s.fallDistance() < 0.0f
                || s.inventory().length == 0
                || s.cursor().length == 0
                || s.effects().size() > 128) {
            throw new IOException("Invalid escrow values");
        }
        HashSet<String> types = new HashSet<String>();
        for (PlayerSnapshot.Effect effect : s.effects()) {
            if (effect != null
                    && effect.type() != null
                    && effect.type().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    && types.add(effect.type())
                    && effect.ticks() >= -1
                    && effect.ticks() != 0
                    && effect.amplifier() >= 0
                    && effect.amplifier() <= 255) continue;
            throw new IOException("Invalid escrow effect");
        }
    }

    private static boolean finite(double... values) {
        for (double value : values) {
            if (Double.isFinite(value)) continue;
            return false;
        }
        return true;
    }

    private static byte[] digest(byte[] b) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static void uuid(DataOutputStream d, UUID u) throws IOException {
        d.writeLong(u.getMostSignificantBits());
        d.writeLong(u.getLeastSignificantBits());
    }

    private static UUID uuid(DataInputStream d) throws IOException {
        return new UUID(d.readLong(), d.readLong());
    }

    private static void blob(DataOutputStream d, byte[] b) throws IOException {
        d.writeInt(b.length);
        d.write(b);
    }

    private static byte[] blob(DataInputStream d) throws IOException {
        int size = d.readInt();
        if (size < 0 || size > 0x200000) {
            throw new IOException("Invalid item blob size");
        }
        byte[] b = d.readNBytes(size);
        if (b.length != size) {
            throw new EOFException("Truncated item bytes");
        }
        return b;
    }
}
