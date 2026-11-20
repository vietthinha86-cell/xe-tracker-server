package com.xetheodoi.xe_tracker_server;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Bộ giải mã và đóng gói khung nhị phân chuẩn JT/T 808 (2013 & 2019) cho thiết bị định vị/camera 4G ô tô.
 */
public final class Jt808Codec {

    public static final byte DELIMITER = 0x7E;
    private static final Charset GBK_OR_ASCII = Charset.forName("UTF-8");

    private Jt808Codec() {}

    public static byte[] unescape(byte[] rawBodyWithoutDelimiters) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(rawBodyWithoutDelimiters.length);
        for (int i = 0; i < rawBodyWithoutDelimiters.length; i++) {
            int b = rawBodyWithoutDelimiters[i] & 0xFF;
            if (b == 0x7D && i + 1 < rawBodyWithoutDelimiters.length) {
                int next = rawBodyWithoutDelimiters[i + 1] & 0xFF;
                if (next == 0x02) {
                    out.write(0x7E);
                    i++;
                    continue;
                } else if (next == 0x01) {
                    out.write(0x7D);
                    i++;
                    continue;
                }
            }
            out.write(b);
        }
        return out.toByteArray();
    }

    public static byte[] escape(byte[] unescaped) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(unescaped.length + 16);
        out.write(DELIMITER);
        for (byte b : unescaped) {
            int v = b & 0xFF;
            if (v == 0x7E) {
                out.write(0x7D);
                out.write(0x02);
            } else if (v == 0x7D) {
                out.write(0x7D);
                out.write(0x01);
            } else {
                out.write(v);
            }
        }
        out.write(DELIMITER);
        return out.toByteArray();
    }

    public static byte xorChecksum(byte[] data, int offset, int length) {
        int acc = 0;
        for (int i = offset; i < offset + length; i++) {
            acc ^= (data[i] & 0xFF);
        }
        return (byte) (acc & 0xFF);
    }

    public static String decodeBcd(byte[] data, int offset, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            int b = data[offset + i] & 0xFF;
            sb.append((b >> 4) & 0x0F);
            sb.append(b & 0x0F);
        }
        return sb.toString();
    }

    public static byte[] encodeBcd(String digits, int byteLen) {
        String clean = digits != null ? digits.replaceAll("[^0-9]", "") : "";
        int targetChars = byteLen * 2;
        if (clean.length() > targetChars) {
            clean = clean.substring(clean.length() - targetChars);
        } else {
            clean = "0".repeat(targetChars - clean.length()) + clean;
        }
        byte[] out = new byte[byteLen];
        for (int i = 0; i < byteLen; i++) {
            int hi = clean.charAt(i * 2) - '0';
            int lo = clean.charAt(i * 2 + 1) - '0';
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static int readUInt16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    public static long readUInt32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24)
                | ((long) (b[off + 1] & 0xFF) << 16)
                | ((long) (b[off + 2] & 0xFF) << 8)
                | ((long) (b[off + 3] & 0xFF));
    }

    public static DecodedFrame parseFrame(byte[] rawPayloadBetween7E) {
        if (rawPayloadBetween7E == null || rawPayloadBetween7E.length < 13) {
            return null;
        }
        byte[] unescaped = unescape(rawPayloadBetween7E);
        if (unescaped.length < 13) {
            return null;
        }
        byte expectedCheck = unescaped[unescaped.length - 1];
        byte actualCheck = xorChecksum(unescaped, 0, unescaped.length - 1);
        if (expectedCheck != actualCheck) {
            return null;
        }

        int msgId = readUInt16(unescaped, 0);
        int props = readUInt16(unescaped, 2);
        int bodyLen = props & 0x03FF;
        boolean hasSubPackage = (props & 0x2000) != 0;
        boolean is2019 = (props & 0x4000) != 0;

        int pos = 4;
        int version2019 = 0;
        int phoneBytes = 6;
        if (is2019) {
            if (unescaped.length < 18) return null;
            version2019 = unescaped[pos++] & 0xFF;
            phoneBytes = 10;
        }
        String phone = decodeBcd(unescaped, pos, phoneBytes);
        pos += phoneBytes;

        int flowId = readUInt16(unescaped, pos);
        pos += 2;

        if (hasSubPackage) {
            pos += 4;
        }
        if (pos + bodyLen > unescaped.length - 1) {
            return null;
        }
        byte[] body = Arrays.copyOfRange(unescaped, pos, pos + bodyLen);
        return new DecodedFrame(msgId, is2019, version2019, phone, flowId, body);
    }

    public static LocationReport decodeLocation0200(byte[] body) {
        if (body == null || body.length < 28) {
            return null;
        }
        long alarmFlags = readUInt32(body, 0);
        long statusFlags = readUInt32(body, 4);
        boolean accOn = (statusFlags & 0x01L) != 0;
        boolean positioned = (statusFlags & 0x02L) != 0;
        boolean south = (statusFlags & 0x04L) != 0;
        boolean west = (statusFlags & 0x08L) != 0;

        double lat = readUInt32(body, 8) / 1_000_000.0;
        if (south) lat = -lat;
        double lng = readUInt32(body, 12) / 1_000_000.0;
        if (west) lng = -lng;

        int altitude = readUInt16(body, 16);
        double speedKmh = readUInt16(body, 18) / 10.0;
        double bearing = (double) readUInt16(body, 20);
        String bcdTime = decodeBcd(body, 22, 6);
        String formattedTime = formatBcdTimestamp(bcdTime);

        return new LocationReport(alarmFlags, statusFlags, accOn, positioned, lat, lng, altitude, speedKmh, bearing, formattedTime);
    }

    public static String formatBcdTimestamp(String yyMMddHHmmss) {
        if (yyMMddHHmmss == null || yyMMddHHmmss.length() != 12) {
            return null;
        }
        return "20" + yyMMddHHmmss.substring(0, 2) + "-"
                + yyMMddHHmmss.substring(2, 4) + "-"
                + yyMMddHHmmss.substring(4, 6) + " "
                + yyMMddHHmmss.substring(6, 8) + ":"
                + yyMMddHHmmss.substring(8, 10) + ":"
                + yyMMddHHmmss.substring(10, 12);
    }

    public static byte[] buildPacket(int msgId, DecodedFrame reqHeader, int replyFlowId, byte[] body) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        int bodyLen = body != null ? body.length : 0;
        int props = bodyLen & 0x03FF;
        if (reqHeader.is2019()) {
            props |= 0x4000;
        }
        raw.write((msgId >> 8) & 0xFF);
        raw.write(msgId & 0xFF);
        raw.write((props >> 8) & 0xFF);
        raw.write(props & 0xFF);
        if (reqHeader.is2019()) {
            raw.write(reqHeader.version2019() & 0xFF);
            byte[] pBytes = encodeBcd(reqHeader.terminalPhone(), 10);
            raw.write(pBytes, 0, pBytes.length);
        } else {
            byte[] pBytes = encodeBcd(reqHeader.terminalPhone(), 6);
            raw.write(pBytes, 0, pBytes.length);
        }
        raw.write((replyFlowId >> 8) & 0xFF);
        raw.write(replyFlowId & 0xFF);
        if (body != null && body.length > 0) {
            raw.write(body, 0, body.length);
        }
        byte[] packetWithoutCheck = raw.toByteArray();
        byte check = xorChecksum(packetWithoutCheck, 0, packetWithoutCheck.length);
        raw.write(check);
        return escape(raw.toByteArray());
    }

    public static byte[] buildGeneralAck(DecodedFrame req, int replyFlowId, int resultCode) {
        byte[] body = new byte[5];
        body[0] = (byte) ((req.flowId() >> 8) & 0xFF);
        body[1] = (byte) (req.flowId() & 0xFF);
        body[2] = (byte) ((req.msgId() >> 8) & 0xFF);
        body[3] = (byte) (req.msgId() & 0xFF);
        body[4] = (byte) (resultCode & 0xFF);
        return buildPacket(0x8001, req, replyFlowId, body);
    }

    public static byte[] buildRegisterAck(DecodedFrame req, int replyFlowId, int resultCode, String authToken) {
        byte[] tokenBytes = authToken != null ? authToken.getBytes(GBK_OR_ASCII) : new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream(3 + tokenBytes.length);
        out.write((req.flowId() >> 8) & 0xFF);
        out.write(req.flowId() & 0xFF);
        out.write(resultCode & 0xFF);
        if (resultCode == 0 && tokenBytes.length > 0) {
            out.write(tokenBytes, 0, tokenBytes.length);
        }
        return buildPacket(0x8100, req, replyFlowId, out.toByteArray());
    }

    public record DecodedFrame(int msgId, boolean is2019, int version2019, String terminalPhone, int flowId, byte[] body) {}

    public record LocationReport(
            long alarmFlags,
            long statusFlags,
            boolean accOn,
            boolean positioned,
            double latitude,
            double longitude,
            int altitude,
            double speedKmh,
            double bearing,
            String timestamp
    ) {}
}
