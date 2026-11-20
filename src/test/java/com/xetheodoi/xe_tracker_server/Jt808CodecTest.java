package com.xetheodoi.xe_tracker_server;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class Jt808CodecTest {

    @Test
    void escapeAndUnescapePreserveDelimiters() {
        byte[] raw = new byte[]{0x02, 0x00, 0x7E, 0x7D, 0x15};
        byte[] escaped = Jt808Codec.escape(raw);
        assertEquals(0x7E, escaped[0]);
        assertEquals(0x7E, escaped[escaped.length - 1]);

        byte[] inner = Arrays.copyOfRange(escaped, 1, escaped.length - 1);
        assertArrayEquals(raw, Jt808Codec.unescape(inner));
    }

    @Test
    void parseRegister0100AndBuildRegisterAck8100() {
        Jt808Codec.DecodedFrame dummyHeader = new Jt808Codec.DecodedFrame(
                0x0100, false, 0, "944526111140", 42, new byte[0]
        );
        byte[] pkt = Jt808Codec.buildPacket(0x0100, dummyHeader, 42, new byte[]{0x00, 0x2C});
        byte[] inner = Arrays.copyOfRange(pkt, 1, pkt.length - 1);

        Jt808Codec.DecodedFrame parsed = Jt808Codec.parseFrame(inner);
        assertNotNull(parsed);
        assertEquals(0x0100, parsed.msgId());
        assertEquals("944526111140", parsed.terminalPhone());
        assertEquals(42, parsed.flowId());

        byte[] ack = Jt808Codec.buildRegisterAck(parsed, 7, 0, "TOKEN123");
        Jt808Codec.DecodedFrame ackParsed = Jt808Codec.parseFrame(Arrays.copyOfRange(ack, 1, ack.length - 1));
        assertNotNull(ackParsed);
        assertEquals(0x8100, ackParsed.msgId());
        assertEquals(42, Jt808Codec.readUInt16(ackParsed.body(), 0));
        assertEquals(0, ackParsed.body()[2]);
    }

    @Test
    void decodeLocation0200ExtractsCoordsAccSpeedBearingAndTimestamp() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        // Alarm DWORD = 0
        body.write(new byte[]{0, 0, 0, 0}, 0, 4);
        // Status DWORD: bit0 ACC=1, bit1 GPS=1 -> 0x00000003
        body.write(new byte[]{0, 0, 0, 3}, 0, 4);
        // Latitude = 10.903210 deg -> 10903210 = 0x00A65EAA
        writeInt32(body, 10_903_210);
        // Longitude = 106.875432 deg -> 106875432 = 0x065EC228
        writeInt32(body, 106_875_432);
        // Altitude = 25m
        writeInt16(body, 25);
        // Speed = 36 (3.6 km/h)
        writeInt16(body, 36);
        // Bearing = 185 deg
        writeInt16(body, 185);
        // BCD Time: 26-11-20 20:41:30
        body.write(new byte[]{0x26, 0x11, 0x20, 0x20, 0x41, 0x30}, 0, 6);

        Jt808Codec.DecodedFrame req = new Jt808Codec.DecodedFrame(
                0x0200, false, 0, "944526111140", 99, body.toByteArray()
        );
        byte[] fullPkt = Jt808Codec.buildPacket(0x0200, req, 99, body.toByteArray());
        Jt808Codec.DecodedFrame parsed = Jt808Codec.parseFrame(Arrays.copyOfRange(fullPkt, 1, fullPkt.length - 1));
        assertNotNull(parsed);

        Jt808Codec.LocationReport report = Jt808Codec.decodeLocation0200(parsed.body());
        assertNotNull(report);
        assertTrue(report.accOn());
        assertTrue(report.positioned());
        assertEquals(10.90321, report.latitude(), 0.00001);
        assertEquals(106.875432, report.longitude(), 0.00001);
        assertEquals(3.6, report.speedKmh(), 0.01);
        assertEquals(185.0, report.bearing(), 0.01);
        assertEquals("2026-11-20 20:41:30", report.timestamp());

        // Checksum tamper rejection
        byte[] corrupted = Arrays.copyOfRange(fullPkt, 1, fullPkt.length - 1);
        corrupted[corrupted.length - 1] ^= 0x55;
        assertNull(Jt808Codec.parseFrame(corrupted));
    }

    private void writeInt32(ByteArrayOutputStream out, int v) {
        out.write((v >> 24) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private void writeInt16(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }
}
