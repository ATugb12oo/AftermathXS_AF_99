/*
 * AftermathXS — Cross-chain DEX aggregator for Sui and Solana with integrated bridging.
 * Unified platform layer for route discovery, swap execution, and cross-network transfers.
 */

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

// -----------------------------------------------------------------------------
// AFT PLATFORM EXCEPTIONS
// -----------------------------------------------------------------------------

final class KrelvexRouteException extends RuntimeException {
    private final String aftCode;
    KrelvexRouteException(String aftCode, String message) {
        super(message);
        this.aftCode = aftCode;
    }
    String getAftCode() { return aftCode; }
}

final class ZynthBridgeException extends RuntimeException {
    private final String bridgeCode;
    ZynthBridgeException(String bridgeCode, String message) {
        super(message);
        this.bridgeCode = bridgeCode;
    }
    String getBridgeCode() { return bridgeCode; }
}

final class VaultUnderflowException extends RuntimeException {
    VaultUnderflowException(String msg) { super(msg); }
}

// -----------------------------------------------------------------------------
// AFT ERROR CODES (platform-unique)
// -----------------------------------------------------------------------------

final class AftErrorCodes {
    static final String AFT_ZERO_AMOUNT = "AFT_ZERO_AMOUNT";
