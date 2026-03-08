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

// -----------------------------------------------------------------------------
// CHAIN IDENTIFIERS (immutable)
// -----------------------------------------------------------------------------

final class AftChainIds {
    static final long CHAIN_SUI = 0x53455L;
    static final long CHAIN_SOLANA = 0x534F4C4C;
    static final String SUI_NETWORK_NAME = "sui-mainnet";
    static final String SOLANA_NETWORK_NAME = "solana-mainnet-beta";
}

// -----------------------------------------------------------------------------
// PLATFORM ADDRESSES (immutable, EIP-55 style 40 hex)
// -----------------------------------------------------------------------------

final class AftAddresses {
    static final String ROUTER_SUI = "0x8F3a2B1c4D5e6f7A8b9C0d1E2f3A4b5C6d7E8f9";
    static final String ROUTER_SOLANA_RELAY = "0x1a2B3c4D5e6F7a8B9c0D1e2F3a4B5c6D7e8F9a0";
    static final String BRIDGE_RELAY_MAIN = "0x3b4C5d6E7f8A9b0C1d2E3f4A5b6C7d8E9f0A1b2";
    static final String FEE_COLLECTOR = "0x5c6D7e8F9a0B1c2D3e4F5a6b7C8d9E0f1A2b3C4";
    static final String TREASURY_VAULT = "0x7d8E9f0A1b2C3d4E5f6A7b8C9d0E1f2A3b4C5d6";
    static final String EMERGENCY_ESCROW = "0x9e0F1a2B3c4D5e6F7a8B9c0D1e2F3a4B5c6D7e8";
    static final String ROUTE_CACHE_ORACLE = "0xB1c2D3e4F5a6b7C8d9E0f1A2b3C4d5E6f7A8b9";
    static final String SLIPPAGE_GUARD = "0xD2e3F4a5b6C7d8E9f0A1b2C3d4E5f6A7b8C9d0";
}

// -----------------------------------------------------------------------------
// U256 SAFE MATH
// -----------------------------------------------------------------------------

final class AftWeiMath {
    private static final BigInteger MAX_U256 = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

    static BigInteger clampU256(BigInteger value) {
        if (value == null || value.signum() < 0) return BigInteger.ZERO;
        if (value.compareTo(MAX_U256) > 0) return MAX_U256;
        return value;
    }

    static BigInteger addSafe(BigInteger a, BigInteger b) {
        return clampU256((a == null ? BigInteger.ZERO : a).add(b == null ? BigInteger.ZERO : b));
    }

    static BigInteger subSafe(BigInteger a, BigInteger b) {
        BigInteger aa = a == null ? BigInteger.ZERO : a;
        BigInteger bb = b == null ? BigInteger.ZERO : b;
        if (bb.compareTo(aa) > 0) return BigInteger.ZERO;
        return aa.subtract(bb);
    }
}

// -----------------------------------------------------------------------------
// ADDRESS VALIDATION (40 hex after 0x)
// -----------------------------------------------------------------------------

final class AftAddressValidator {
    private static final Pattern EIP55_HEX = Pattern.compile("^0x[0-9a-fA-F]{40}$");

    static boolean isValid(String address) {
        return address != null && EIP55_HEX.matcher(address).matches();
    }

    static void requireValid(String address) {
        if (!isValid(address)) throw new KrelvexRouteException(AftErrorCodes.AFT_INVALID_ADDRESS, "Bad address: " + address);
    }
}

// -----------------------------------------------------------------------------
// EVENTS (platform event types)
// -----------------------------------------------------------------------------

final class SwapExecutedEvent {
    final String requestId;
    final long chainId;
    final String tokenIn;
    final String tokenOut;
    final BigInteger amountIn;
    final BigInteger amountOut;
    final Instant timestamp;

    SwapExecutedEvent(String requestId, long chainId, String tokenIn, String tokenOut,
                      BigInteger amountIn, BigInteger amountOut, Instant timestamp) {
        this.requestId = requestId;
        this.chainId = chainId;
        this.tokenIn = tokenIn;
        this.tokenOut = tokenOut;
        this.amountIn = amountIn;
        this.amountOut = amountOut;
        this.timestamp = timestamp;
    }
}

final class BridgeInitiatedEvent {
    final String bridgeId;
    final long fromChainId;
    final long toChainId;
    final String token;
    final BigInteger amount;
    final String recipient;
    final Instant timestamp;

    BridgeInitiatedEvent(String bridgeId, long fromChainId, long toChainId, String token,
                         BigInteger amount, String recipient, Instant timestamp) {
        this.bridgeId = bridgeId;
        this.fromChainId = fromChainId;
        this.toChainId = toChainId;
        this.token = token;
        this.amount = amount;
        this.recipient = recipient;
        this.timestamp = timestamp;
    }
}

final class RouteQuotedEvent {
    final String quoteId;
    final long chainId;
    final List<String> path;
    final BigInteger estimatedOut;
    final long validUntilMs;
}

// -----------------------------------------------------------------------------
// TOKEN METADATA
// -----------------------------------------------------------------------------

final class AftTokenInfo {
    final String address;
    final String symbol;
    final int decimals;
    final long chainId;

    AftTokenInfo(String address, String symbol, int decimals, long chainId) {
        this.address = address;
        this.symbol = symbol;
        this.decimals = decimals;
        this.chainId = chainId;
    }
}

// -----------------------------------------------------------------------------
// LIQUIDITY POOL (abstract representation)
// -----------------------------------------------------------------------------

final class AftPoolInfo {
    final String poolId;
    final String tokenA;
    final String tokenB;
    final BigInteger reserveA;
    final BigInteger reserveB;
    final long chainId;
    final String dexLabel;

    AftPoolInfo(String poolId, String tokenA, String tokenB, BigInteger reserveA, BigInteger reserveB, long chainId, String dexLabel) {
        this.poolId = poolId;
        this.tokenA = tokenA;
        this.tokenB = tokenB;
        this.reserveA = reserveA;
        this.reserveB = reserveB;
        this.chainId = chainId;
        this.dexLabel = dexLabel;
    }

    BigInteger getReserveFor(String token) {
        if (token.equalsIgnoreCase(tokenA)) return reserveA;
        if (token.equalsIgnoreCase(tokenB)) return reserveB;
        return BigInteger.ZERO;
    }
}

// -----------------------------------------------------------------------------
// ROUTE STEP
// -----------------------------------------------------------------------------

final class AftRouteStep {
    final String poolId;
    final String tokenIn;
    final String tokenOut;
    final String dexLabel;
    final BigInteger amountIn;
    final BigInteger amountOut;

    AftRouteStep(String poolId, String tokenIn, String tokenOut, String dexLabel, BigInteger amountIn, BigInteger amountOut) {
        this.poolId = poolId;
        this.tokenIn = tokenIn;
        this.tokenOut = tokenOut;
        this.dexLabel = dexLabel;
        this.amountIn = amountIn;
        this.amountOut = amountOut;
    }
}

// -----------------------------------------------------------------------------
// QUOTE RESULT
// -----------------------------------------------------------------------------

final class AftQuoteResult {
    final List<AftRouteStep> steps;
    final BigInteger amountIn;
    final BigInteger amountOut;
    final BigInteger feeAmount;
    final long validUntilMs;
    final String quoteId;

    AftQuoteResult(List<AftRouteStep> steps, BigInteger amountIn, BigInteger amountOut, BigInteger feeAmount, long validUntilMs, String quoteId) {
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
        this.amountIn = amountIn;
        this.amountOut = amountOut;
        this.feeAmount = feeAmount;
        this.validUntilMs = validUntilMs;
        this.quoteId = quoteId;
    }
}

// -----------------------------------------------------------------------------
// BRIDGE QUOTE
// -----------------------------------------------------------------------------

final class ZynthBridgeQuote {
    final String quoteId;
    final long fromChainId;
    final long toChainId;
    final String token;
    final BigInteger amount;
    final BigInteger estimatedReceived;
    final BigInteger relayFee;
    final long validUntilMs;

    ZynthBridgeQuote(String quoteId, long fromChainId, long toChainId, String token, BigInteger amount,
                     BigInteger estimatedReceived, BigInteger relayFee, long validUntilMs) {
        this.quoteId = quoteId;
        this.fromChainId = fromChainId;
        this.toChainId = toChainId;
        this.token = token;
        this.amount = amount;
        this.estimatedReceived = estimatedReceived;
        this.relayFee = relayFee;
        this.validUntilMs = validUntilMs;
    }
}

// -----------------------------------------------------------------------------
// CORE AGGREGATOR ENGINE
// -----------------------------------------------------------------------------

final class AftermathXSAggregator {
    private final Map<Long, List<AftPoolInfo>> poolsByChain = new ConcurrentHashMap<>();
    private final Set<String> whitelistedTokens = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final ReentrantLock quoteLock = new ReentrantLock();
    private static final BigInteger FEE_BPS_MAX = BigInteger.valueOf(100);
    private static final BigInteger FEE_BPS_DEFAULT = BigInteger.valueOf(30);
    private final BigInteger feeBps;
    private final long quoteTtlMs;

