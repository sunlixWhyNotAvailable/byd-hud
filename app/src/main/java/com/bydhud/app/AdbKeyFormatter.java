package com.bydhud.app;

//keeps adb key serialization isolated so the local bridge can sign packets without leaking crypto formatting into callers.

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

//defines the AdbKeyFormatter module boundary so related behavior stays readable inside one unit.
final class AdbKeyFormatter {
    private static final int MODULUS_BYTES = 256;
    private static final int MODULUS_WORDS = MODULUS_BYTES / 4;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private AdbKeyFormatter() {
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static String formatPublicKey(RSAPublicKey publicKey) {
        return Base64.getEncoder().encodeToString(encodePublicKey(publicKey)) + " bydhud@dilink";
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static byte[] encodePublicKey(RSAPublicKey publicKey) {
        BigInteger modulus = publicKey.getModulus();
        BigInteger exponent = publicKey.getPublicExponent();
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0 = modulus.mod(two32);
        BigInteger n0inv = n0.modInverse(two32).negate().mod(two32);
        BigInteger r = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8);
        BigInteger rr = r.multiply(r).mod(modulus);

        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(MODULUS_WORDS);
        buffer.putInt((int) n0inv.longValue());
        buffer.put(toFixedLittleEndian(modulus, MODULUS_BYTES));
        buffer.put(toFixedLittleEndian(rr, MODULUS_BYTES));
        buffer.putInt(exponent.intValue());
        return buffer.array();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static byte[] toFixedLittleEndian(BigInteger value, int length) {
        byte[] bigEndian = value.toByteArray();
        int start = 0;
        while (start < bigEndian.length - 1 && bigEndian[start] == 0) {
            start++;
        }
        int unsignedLength = bigEndian.length - start;
        if (unsignedLength > length) {
            throw new IllegalArgumentException("RSA value does not fit ADB key field");
        }
        byte[] littleEndian = new byte[length];
        for (int i = 0; i < unsignedLength; i++) {
            littleEndian[i] = bigEndian[bigEndian.length - 1 - i];
        }
        return littleEndian;
    }
}
