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

    AftermathXSAggregator(BigInteger feeBps, long quoteTtlMs) {
        this.feeBps = feeBps == null || feeBps.compareTo(FEE_BPS_MAX) > 0 ? FEE_BPS_DEFAULT : feeBps;
        this.quoteTtlMs = quoteTtlMs <= 0 ? 30_000L : quoteTtlMs;
    }

    void registerPool(AftPoolInfo pool) {
        if (pool == null) return;
        poolsByChain.computeIfAbsent(pool.chainId, k -> new CopyOnWriteArrayList<>()).add(pool);
        whitelistedTokens.add(pool.tokenA);
        whitelistedTokens.add(pool.tokenB);
    }

    void setPaused(boolean value) {
        paused.set(value);
    }

    boolean isPaused() {
        return paused.get();
    }

    void requireNotPaused() {
        if (paused.get()) throw new KrelvexRouteException(AftErrorCodes.AFT_PAUSED, "Platform paused");
    }

    void requireWhitelisted(String token) {
        if (!whitelistedTokens.contains(token))
            throw new KrelvexRouteException(AftErrorCodes.AFT_TOKEN_NOT_WHITELISTED, "Token: " + token);
    }

    AftQuoteResult getQuote(long chainId, String tokenIn, String tokenOut, BigInteger amountIn, BigInteger minOut, long deadlineMs) {
        requireNotPaused();
        if (amountIn == null || amountIn.signum() <= 0)
            throw new KrelvexRouteException(AftErrorCodes.AFT_ZERO_AMOUNT, "amountIn must be positive");
        if (chainId != AftChainIds.CHAIN_SUI && chainId != AftChainIds.CHAIN_SOLANA)
            throw new KrelvexRouteException(AftErrorCodes.AFT_CHAIN_UNSUPPORTED, "chainId=" + chainId);
        requireWhitelisted(tokenIn);
        requireWhitelisted(tokenOut);
        if (deadlineMs > 0 && System.currentTimeMillis() > deadlineMs)
            throw new KrelvexRouteException(AftErrorCodes.AFT_DEADLINE_PASSED, "Deadline passed");

        List<AftPoolInfo> pools = poolsByChain.get(chainId);
        if (pools == null || pools.isEmpty())
            throw new KrelvexRouteException(AftErrorCodes.AFT_POOL_NOT_FOUND, "No pools for chain");

        quoteLock.lock();
        try {
            List<AftRouteStep> steps = findBestRoute(pools, tokenIn, tokenOut, amountIn);
            if (steps.isEmpty())
                throw new KrelvexRouteException(AftErrorCodes.AFT_INVALID_ROUTE, "No route");
            BigInteger out = steps.get(steps.size() - 1).amountOut;
            BigInteger fee = out.multiply(feeBps).divide(BigInteger.valueOf(10_000));
            BigInteger outAfterFee = out.subtract(fee);
            if (minOut != null && minOut.signum() > 0 && outAfterFee.compareTo(minOut) < 0)
                throw new KrelvexRouteException(AftErrorCodes.AFT_SLIPPAGE_EXCEEDED, "minOut not met");
            long validUntil = System.currentTimeMillis() + quoteTtlMs;
            String quoteId = "AFT-Q-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            return new AftQuoteResult(steps, amountIn, outAfterFee, fee, validUntil, quoteId);
        } finally {
            quoteLock.unlock();
        }
    }

    private List<AftRouteStep> findBestRoute(List<AftPoolInfo> pools, String tokenIn, String tokenOut, BigInteger amountIn) {
        if (tokenIn.equalsIgnoreCase(tokenOut)) return Collections.emptyList();
        List<AftRouteStep> direct = tryDirectRoute(pools, tokenIn, tokenOut, amountIn);
        if (!direct.isEmpty()) return direct;
        return tryTwoHopRoute(pools, tokenIn, tokenOut, amountIn);
    }

    private List<AftRouteStep> tryDirectRoute(List<AftPoolInfo> pools, String tokenIn, String tokenOut, BigInteger amountIn) {
        for (AftPoolInfo p : pools) {
            if (!((p.tokenA.equalsIgnoreCase(tokenIn) && p.tokenB.equalsIgnoreCase(tokenOut)) ||
                  (p.tokenA.equalsIgnoreCase(tokenOut) && p.tokenB.equalsIgnoreCase(tokenIn)))) continue;
            BigInteger reserveIn = p.getReserveFor(tokenIn);
            BigInteger reserveOut = p.getReserveFor(tokenOut);
            if (reserveIn.signum() == 0) continue;
            BigInteger amountOut = amountIn.multiply(reserveOut).divide(reserveIn);
            if (amountOut.signum() == 0) continue;
            AftRouteStep step = new AftRouteStep(p.poolId, tokenIn, tokenOut, p.dexLabel, amountIn, amountOut);
            return List.of(step);
        }
        return Collections.emptyList();
    }

    private List<AftRouteStep> tryTwoHopRoute(List<AftPoolInfo> pools, String tokenIn, String tokenOut, BigInteger amountIn) {
        Set<String> midTokens = new HashSet<>();
        for (AftPoolInfo p : pools) {
            if (p.tokenA.equalsIgnoreCase(tokenIn) || p.tokenB.equalsIgnoreCase(tokenIn)) {
                String mid = p.tokenA.equalsIgnoreCase(tokenIn) ? p.tokenB : p.tokenA;
                midTokens.add(mid);
            }
        }
        List<AftRouteStep> best = Collections.emptyList();
        BigInteger bestOut = BigInteger.ZERO;
        for (String mid : midTokens) {
            List<AftRouteStep> first = tryDirectRoute(pools, tokenIn, mid, amountIn);
            if (first.isEmpty()) continue;
            BigInteger midAmount = first.get(0).amountOut;
            List<AftRouteStep> second = tryDirectRoute(pools, mid, tokenOut, midAmount);
            if (second.isEmpty()) continue;
            BigInteger finalOut = second.get(0).amountOut;
            if (finalOut.compareTo(bestOut) > 0) {
                bestOut = finalOut;
                best = new ArrayList<>(first);
                best.addAll(second);
            }
        }
        return best;
    }

    SwapExecutedEvent executeSwap(String requestId, long chainId, String tokenIn, String tokenOut, BigInteger amountIn, BigInteger minOut, long deadlineMs) {
        AftQuoteResult quote = getQuote(chainId, tokenIn, tokenOut, amountIn, minOut, deadlineMs);
        if (System.currentTimeMillis() > quote.validUntilMs)
            throw new KrelvexRouteException(AftErrorCodes.AFT_ROUTE_STALE, "Quote expired");
        requireNotPaused();
        return new SwapExecutedEvent(requestId, chainId, tokenIn, tokenOut, quote.amountIn, quote.amountOut, Instant.now());
    }
}

// -----------------------------------------------------------------------------
// BRIDGE ENGINE
// -----------------------------------------------------------------------------

final class ZynthBridgeEngine {
    private final AtomicBoolean relayBusy = new AtomicBoolean(false);
    private final Map<String, ZynthBridgeQuote> activeQuotes = new ConcurrentHashMap<>();
    private final long quoteTtlMs;
    private static final BigInteger MIN_BRIDGE_AMOUNT = new BigInteger("1000000000000000");
    private static final BigInteger RELAY_FEE_BPS = BigInteger.valueOf(10);

    ZynthBridgeEngine(long quoteTtlMs) {
        this.quoteTtlMs = quoteTtlMs <= 0 ? 60_000L : quoteTtlMs;
    }

    ZynthBridgeQuote getQuote(long fromChainId, long toChainId, String token, BigInteger amount) {
        if (amount == null || amount.compareTo(MIN_BRIDGE_AMOUNT) < 0)
            throw new ZynthBridgeException(ZynthBridgeCodes.ZB_AMOUNT_TOO_LOW, "Amount below minimum");
        if (fromChainId == toChainId)
            throw new ZynthBridgeException(ZynthBridgeCodes.ZB_SOURCE_CHAIN, "Same chain");
        if (fromChainId != AftChainIds.CHAIN_SUI && fromChainId != AftChainIds.CHAIN_SOLANA)
            throw new ZynthBridgeException(ZynthBridgeCodes.ZB_SOURCE_CHAIN, "Unsupported source");
        if (toChainId != AftChainIds.CHAIN_SUI && toChainId != AftChainIds.CHAIN_SOLANA)
            throw new ZynthBridgeException(ZynthBridgeCodes.ZB_DEST_CHAIN, "Unsupported dest");
        BigInteger relayFee = amount.multiply(RELAY_FEE_BPS).divide(BigInteger.valueOf(10_000));
        BigInteger estimatedReceived = amount.subtract(relayFee);
        long validUntil = System.currentTimeMillis() + quoteTtlMs;
        String quoteId = "ZB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ZynthBridgeQuote q = new ZynthBridgeQuote(quoteId, fromChainId, toChainId, token, amount, estimatedReceived, relayFee, validUntil);
        activeQuotes.put(quoteId, q);
        return q;
    }

    BridgeInitiatedEvent initiateBridge(String quoteId, String recipient) {
        if (!AftAddressValidator.isValid(recipient))
            throw new ZynthBridgeException(ZynthBridgeCodes.ZB_SOURCE_CHAIN, "Invalid recipient");
        ZynthBridgeQuote q = activeQuotes.get(quoteId);
        if (q == null) throw new ZynthBridgeException(ZynthBridgeCodes.ZB_NONCE_COLLISION, "Quote not found");
        if (System.currentTimeMillis() > q.validUntilMs)
            throw new ZynthBridgeException(ZynthBridgeCodes.ZB_RELAY_BUSY, "Quote expired");
        if (!relayBusy.compareAndSet(false, true)) throw new ZynthBridgeException(ZynthBridgeCodes.ZB_RELAY_BUSY, "Relay busy");
        try {
            String bridgeId = "BR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            return new BridgeInitiatedEvent(bridgeId, q.fromChainId, q.toChainId, q.token, q.amount, recipient, Instant.now());
        } finally {
            relayBusy.set(false);
            activeQuotes.remove(quoteId);
        }
    }
}

// -----------------------------------------------------------------------------
// UNIFIED PLATFORM (AftermathXS core)
// -----------------------------------------------------------------------------

public final class AftermathXSCore {
    private final AftermathXSAggregator aggregator;
    private final ZynthBridgeEngine bridgeEngine;
    private final String platformVersion;
    private static final String VERSION = "2.1.9-krelvex";

    public AftermathXSCore() {
        this.aggregator = new AftermathXSAggregator(BigInteger.valueOf(25), 45_000L);
        this.bridgeEngine = new ZynthBridgeEngine(90_000L);
        this.platformVersion = VERSION;
        seedDefaultPools();
    }

    private void seedDefaultPools() {
        BigInteger r1 = new BigInteger("1000000000000000000000");
        BigInteger r2 = new BigInteger("2000000000000000000000");
        BigInteger r3 = new BigInteger("500000000000000000000");
        String suiUsdc = "0x2a1B3c4D5e6F7a8B9c0D1e2F3a4B5c6D7e8F9a1";
        String suiUsdt = "0x4b5C6d7E8f9A0b1C2d3E4f5A6b7C8d9E0f1A2b3";
        String suiSui = "0x6c7D8e9F0a1B2c3D4e5F6a7B8c9D0e1F2a3B4c5";
        String solUsdc = "0x8d9E0f1A2b3C4d5E6f7A8b9C0d1E2f3A4b5C6d7";
        String solSol = "0x0e1F2a3B4c5D6e7F8a9B0c1D2e3F4a5B6c7D8e9";
        aggregator.registerPool(new AftPoolInfo("sui-pool-1", suiSui, suiUsdc, r1, r2, AftChainIds.CHAIN_SUI, "KrelvexSui"));
        aggregator.registerPool(new AftPoolInfo("sui-pool-2", suiUsdc, suiUsdt, r2, r3, AftChainIds.CHAIN_SUI, "ZynthSui"));
        aggregator.registerPool(new AftPoolInfo("sol-pool-1", solSol, solUsdc, r1, r2, AftChainIds.CHAIN_SOLANA, "KrelvexSol"));
    }

    public AftermathXSAggregator getAggregator() { return aggregator; }
    public ZynthBridgeEngine getBridgeEngine() { return bridgeEngine; }
    public String getPlatformVersion() { return platformVersion; }

    public AftQuoteResult quoteSwap(long chainId, String tokenIn, String tokenOut, BigInteger amountIn, BigInteger minOut, long deadlineMs) {
        return aggregator.getQuote(chainId, tokenIn, tokenOut, amountIn, minOut, deadlineMs);
    }

    public SwapExecutedEvent executeSwap(String requestId, long chainId, String tokenIn, String tokenOut, BigInteger amountIn, BigInteger minOut, long deadlineMs) {
        return aggregator.executeSwap(requestId, chainId, tokenIn, tokenOut, amountIn, minOut, deadlineMs);
    }

    public ZynthBridgeQuote getBridgeQuote(long fromChainId, long toChainId, String token, BigInteger amount) {
        return bridgeEngine.getQuote(fromChainId, toChainId, token, amount);
    }

    public BridgeInitiatedEvent initiateBridge(String quoteId, String recipient) {
        return bridgeEngine.initiateBridge(quoteId, recipient);
    }
}

// =============================================================================
// AF_99 APPLICATION
// =============================================================================

final class AF_99Config {
    static final int DEFAULT_HTTP_PORT = 9947;
    static final String APP_NAME = "AF_99";
    static final String BIND_HOST = "0.0.0.0";
    static final int MAX_QUOTE_CACHE = 512;
    static final long CACHE_TTL_MS = 20_000L;
}

final class AF_99QuoteCache {
    private final Map<String, CachedQuoteEntry> cache = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final int maxSize;
    private final LinkedHashMap<String, Long> accessOrder = new LinkedHashMap<>(16, 0.75f, true);

    AF_99QuoteCache(long ttlMs, int maxSize) {
        this.ttlMs = ttlMs;
        this.maxSize = maxSize;
    }

    static final class CachedQuoteEntry {
        final AftQuoteResult result;
        final long expiresAt;

        CachedQuoteEntry(AftQuoteResult result, long expiresAt) {
            this.result = result;
            this.expiresAt = expiresAt;
        }
    }

    void put(String key, AftQuoteResult result) {
        if (key == null || result == null) return;
        evictIfNeeded();
        long expiresAt = System.currentTimeMillis() + ttlMs;
        cache.put(key, new CachedQuoteEntry(result, expiresAt));
        accessOrder.put(key, System.currentTimeMillis());
    }

    AftQuoteResult get(String key) {
        CachedQuoteEntry e = cache.get(key);
        if (e == null) return null;
        if (System.currentTimeMillis() > e.expiresAt) {
            cache.remove(key);
            accessOrder.remove(key);
            return null;
        }
        accessOrder.put(key, System.currentTimeMillis());
        return e.result;
    }

    private void evictIfNeeded() {
        if (cache.size() < maxSize) return;
        Iterator<Map.Entry<String, Long>> it = accessOrder.entrySet().iterator();
        while (it.hasNext() && cache.size() >= maxSize) {
            Map.Entry<String, Long> next = it.next();
            cache.remove(next.getKey());
            it.remove();
        }
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
        accessOrder.entrySet().removeIf(entry -> entry.getValue() < now - ttlMs);
    }
}

final class AF_99Request {
    String action;
    Long chainId;
    String tokenIn;
    String tokenOut;
    String amountIn;
    String minOut;
    String quoteId;
    String recipient;
    Long deadlineMs;

    static AF_99Request fromMap(Map<String, String> params) {
        AF_99Request r = new AF_99Request();
        r.action = params.get("action");
        String c = params.get("chainId");
        if (c != null) try { r.chainId = Long.parseLong(c); } catch (NumberFormatException ignored) { }
        r.tokenIn = params.get("tokenIn");
        r.tokenOut = params.get("tokenOut");
        r.amountIn = params.get("amountIn");
        r.minOut = params.get("minOut");
        r.quoteId = params.get("quoteId");
        r.recipient = params.get("recipient");
        String d = params.get("deadlineMs");
        if (d != null) try { r.deadlineMs = Long.parseLong(d); } catch (NumberFormatException ignored) { }
        return r;
    }
}

final class AF_99Response {
    boolean success;
    String errorCode;
    String message;
    Map<String, Object> data;

    static AF_99Response ok(Map<String, Object> data) {
        AF_99Response res = new AF_99Response();
        res.success = true;
        res.data = data;
        return res;
    }

    static AF_99Response fail(String errorCode, String message) {
        AF_99Response res = new AF_99Response();
        res.success = false;
        res.errorCode = errorCode;
        res.message = message;
        return res;
    }
}

final class AF_99Json {
    private static String escape(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else sb.append(c);
        }
        sb.append("\"");
        return sb.toString();
    }

    static String toJson(Map<String, Object> map) {
        if (map == null) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(escape(e.getKey())).append(":");
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof String) sb.append(escape((String) v));
            else if (v instanceof Number) sb.append(v);
            else if (v instanceof Boolean) sb.append((Boolean) v ? "true" : "false");
            else if (v instanceof Map) sb.append(toJson((Map<String, Object>) v));
            else if (v instanceof List) sb.append(toJsonList((List<?>) v));
            else sb.append(escape(String.valueOf(v)));
        }
        sb.append("}");
        return sb.toString();
    }

    private static String toJsonList(List<?> list) {
        if (list == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            Object v = list.get(i);
            if (v == null) sb.append("null");
            else if (v instanceof String) sb.append(escape((String) v));
            else if (v instanceof Number) sb.append(v);
            else if (v instanceof Boolean) sb.append((Boolean) v ? "true" : "false");
            else if (v instanceof Map) sb.append(toJson((Map<String, Object>) v));
            else sb.append(escape(String.valueOf(v)));
        }
        sb.append("]");
        return sb.toString();
    }
}

final class AF_99HttpHandler {
    private final AftermathXSCore core;
    private final AF_99QuoteCache quoteCache;

    AF_99HttpHandler(AftermathXSCore core, AF_99QuoteCache quoteCache) {
        this.core = core;
        this.quoteCache = quoteCache;
    }

    String handle(String path, Map<String, String> queryParams, String body) {
        if (path == null) path = "/";
        path = path.split("\\?")[0];
        if ("/quote".equals(path)) return handleQuote(queryParams);
        if ("/swap".equals(path)) return handleSwap(queryParams);
        if ("/bridge/quote".equals(path)) return handleBridgeQuote(queryParams);
        if ("/bridge/init".equals(path)) return handleBridgeInit(queryParams);
        if ("/status".equals(path)) return handleStatus();
        if ("/health".equals(path)) return handleHealth();
        return AF_99Json.toJson(Map.of("error", "not_found", "path", path));
    }

    private String handleQuote(Map<String, String> params) {
        AF_99Request req = AF_99Request.fromMap(params);
        try {
            if (req.chainId == null || req.tokenIn == null || req.tokenOut == null || req.amountIn == null) {
                return AF_99Json.toJson(Map.of("success", false, "errorCode", AftErrorCodes.AFT_INVALID_ADDRESS, "message", "Missing params"));
            }
            BigInteger amountIn = new BigInteger(req.amountIn);
            BigInteger minOut = req.minOut != null ? new BigInteger(req.minOut) : BigInteger.ZERO;
            long deadline = req.deadlineMs != null ? req.deadlineMs : System.currentTimeMillis() + 120_000;
            String cacheKey = req.chainId + ":" + req.tokenIn + ":" + req.tokenOut + ":" + req.amountIn;
            AftQuoteResult cached = quoteCache.get(cacheKey);
            if (cached != null) {
                Map<String, Object> data = new HashMap<>();
                data.put("quoteId", cached.quoteId);
                data.put("amountIn", cached.amountIn.toString());
                data.put("amountOut", cached.amountOut.toString());
                data.put("feeAmount", cached.feeAmount.toString());
                data.put("validUntilMs", cached.validUntilMs);
                data.put("cached", true);
                return AF_99Json.toJson(Map.of("success", true, "data", data));
            }
            AftQuoteResult quote = core.quoteSwap(req.chainId, req.tokenIn, req.tokenOut, amountIn, minOut, deadline);
            quoteCache.put(cacheKey, quote);
            Map<String, Object> data = new HashMap<>();
            data.put("quoteId", quote.quoteId);
            data.put("amountIn", quote.amountIn.toString());
            data.put("amountOut", quote.amountOut.toString());
            data.put("feeAmount", quote.feeAmount.toString());
            data.put("validUntilMs", quote.validUntilMs);
            return AF_99Json.toJson(Map.of("success", true, "data", data));
        } catch (KrelvexRouteException e) {
            return AF_99Json.toJson(Map.of("success", false, "errorCode", e.getAftCode(), "message", e.getMessage()));
        } catch (Exception e) {
            return AF_99Json.toJson(Map.of("success", false, "errorCode", "AFT_INTEGRITY_CHECK", "message", e.getMessage()));
        }
    }

    private String handleSwap(Map<String, String> params) {
        AF_99Request req = AF_99Request.fromMap(params);
        try {
            if (req.chainId == null || req.tokenIn == null || req.tokenOut == null || req.amountIn == null) {
                return AF_99Json.toJson(Map.of("success", false, "errorCode", AftErrorCodes.AFT_INVALID_ADDRESS, "message", "Missing params"));
            }
