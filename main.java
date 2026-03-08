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
    static final String AFT_INVALID_ROUTE = "AFT_INVALID_ROUTE";
    static final String AFT_SLIPPAGE_EXCEEDED = "AFT_SLIPPAGE_EXCEEDED";
    static final String AFT_CHAIN_UNSUPPORTED = "AFT_CHAIN_UNSUPPORTED";
    static final String AFT_POOL_NOT_FOUND = "AFT_POOL_NOT_FOUND";
    static final String AFT_BRIDGE_QUOTE_STALE = "AFT_BRIDGE_QUOTE_STALE";
    static final String AFT_INSUFFICIENT_LIQUIDITY = "AFT_INSUFFICIENT_LIQUIDITY";
    static final String AFT_DEADLINE_PASSED = "AFT_DEADLINE_PASSED";
    static final String AFT_UNAUTHORIZED_CALLER = "AFT_UNAUTHORIZED_CALLER";
    static final String AFT_TOKEN_NOT_WHITELISTED = "AFT_TOKEN_NOT_WHITELISTED";
    static final String AFT_BRIDGE_TX_FAILED = "AFT_BRIDGE_TX_FAILED";
    static final String AFT_ROUTE_STALE = "AFT_ROUTE_STALE";
    static final String AFT_FEE_CAP_EXCEEDED = "AFT_FEE_CAP_EXCEEDED";
    static final String AFT_PAUSED = "AFT_PAUSED";
    static final String AFT_INTEGRITY_CHECK = "AFT_INTEGRITY_CHECK";
    static final String AFT_INVALID_ADDRESS = "AFT_INVALID_ADDRESS";

    static String describe(String code) {
        if (code == null) return "Unknown";
        switch (code) {
            case AFT_ZERO_AMOUNT: return "Amount must be positive";
            case AFT_INVALID_ROUTE: return "No valid route for pair";
            case AFT_SLIPPAGE_EXCEEDED: return "Output below minimum";
            case AFT_CHAIN_UNSUPPORTED: return "Chain not supported";
            case AFT_POOL_NOT_FOUND: return "Liquidity pool not found";
            case AFT_BRIDGE_QUOTE_STALE: return "Bridge quote expired";
            case AFT_INSUFFICIENT_LIQUIDITY: return "Insufficient pool liquidity";
            case AFT_DEADLINE_PASSED: return "Transaction deadline passed";
            case AFT_UNAUTHORIZED_CALLER: return "Caller not authorized";
            case AFT_TOKEN_NOT_WHITELISTED: return "Token not whitelisted";
            case AFT_BRIDGE_TX_FAILED: return "Bridge transaction failed";
            case AFT_ROUTE_STALE: return "Route data stale";
            case AFT_FEE_CAP_EXCEEDED: return "Fee exceeds cap";
            case AFT_PAUSED: return "Operations paused";
            case AFT_INTEGRITY_CHECK: return "Integrity check failed";
            case AFT_INVALID_ADDRESS: return "Invalid address format";
            default: return "Unknown: " + code;
        }
    }
}

// -----------------------------------------------------------------------------
// BRIDGE ERROR CODES
// -----------------------------------------------------------------------------

final class ZynthBridgeCodes {
    static final String ZB_SOURCE_CHAIN = "ZB_SOURCE_CHAIN";
    static final String ZB_DEST_CHAIN = "ZB_DEST_CHAIN";
    static final String ZB_AMOUNT_TOO_LOW = "ZB_AMOUNT_TOO_LOW";
    static final String ZB_RELAY_BUSY = "ZB_RELAY_BUSY";
    static final String ZB_NONCE_COLLISION = "ZB_NONCE_COLLISION";
}
