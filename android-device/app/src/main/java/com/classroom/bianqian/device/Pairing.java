package com.classroom.bianqian.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 配对与主题派生。规则必须与 Windows 端 pairing.py 完全一致：
 * channel = sha256("ID:KEY").hex().substring(0,20)，主题 = ns1/&lt;channel&gt;/in
 */
public final class Pairing {

    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final String PREFIX = "NS1";
    private static final char[] B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final SecureRandom RND = new SecureRandom();

    private Pairing() {
    }

    public static String channelId(String id, String key) {
        try {
            String raw = id.trim().toUpperCase() + ":" + key.trim().toUpperCase();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                sb.append(String.format("%02x", d[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String base(String id, String key) {
        return "ns1/" + channelId(id, key);
    }

    public static String randomCode(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(ALPHABET.charAt(RND.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** NS1|ID|KEY|base32(名称) */
    public static String pairingString(String id, String key, String name) {
        return PREFIX + "|" + id + "|" + key + "|" + base32(name);
    }

    public static String[] parse(String text) {
        if (text == null) {
            return null;
        }
        String[] p = text.trim().split("\\|");
        if (p.length < 3 || !PREFIX.equals(p[0])) {
            return null;
        }
        String id = p[1].trim().toUpperCase();
        String key = p[2].trim().toUpperCase();
        if (id.length() < 4 || key.length() < 6) {
            return null;
        }
        String name = p.length > 3 ? unBase32(p[3]) : "";
        return new String[]{id, key, name};
    }

    public static String base32(String s) {
        if (s == null) {
            s = "";
        }
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(B32[(buffer >> (bits - 5)) & 0x1f]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(B32[(buffer << (5 - bits)) & 0x1f]);
        }
        return out.toString();
    }

    public static String unBase32(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : s.toUpperCase().toCharArray()) {
            int v = new String(B32).indexOf(c);
            if (v < 0) {
                continue;
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        try {
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
