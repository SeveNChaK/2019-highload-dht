package ru.mail.polis.service.alex;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeSet;

public class HashingTopology{
    private final int range;
    private final HashMap<Integer, String> mappedServers;
    private final String me;

    public HashingTopology(@NotNull final Set<String> servers,
                                     @NotNull final String me,
                                     final int range) {
        this.range = range;
        this.mappedServers = new HashMap<>(2 * range + 1);
        this.me = me;
        int offset = 0;
        for (final String server : servers) {
            for (int i = -range + offset; i <= range; i += servers.size()) {
                this.mappedServers.put(i, server);
            }
            offset++;
        }
    }

    String primaryFor(@NotNull ByteBuffer key) {
        return mappedServers.get(hashCode(key));
    }

    boolean isMe(@NotNull String node) {
        return me.equals(node);
    }

    Set<String> all() {
        return new TreeSet<>(mappedServers.values());
    }

    private int hashCode(Object o) {
        return o.hashCode() % range;
    }
}