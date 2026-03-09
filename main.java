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
            BigInteger amountIn = new BigInteger(req.amountIn);
            BigInteger minOut = req.minOut != null ? new BigInteger(req.minOut) : BigInteger.ZERO;
            long deadline = req.deadlineMs != null ? req.deadlineMs : System.currentTimeMillis() + 120_000;
            String requestId = "req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            SwapExecutedEvent ev = core.executeSwap(requestId, req.chainId, req.tokenIn, req.tokenOut, amountIn, minOut, deadline);
            Map<String, Object> data = new HashMap<>();
            data.put("requestId", ev.requestId);
            data.put("chainId", ev.chainId);
            data.put("tokenIn", ev.tokenIn);
            data.put("tokenOut", ev.tokenOut);
            data.put("amountIn", ev.amountIn.toString());
            data.put("amountOut", ev.amountOut.toString());
            data.put("timestamp", ev.timestamp.toString());
            return AF_99Json.toJson(Map.of("success", true, "data", data));
        } catch (KrelvexRouteException e) {
            return AF_99Json.toJson(Map.of("success", false, "errorCode", e.getAftCode(), "message", e.getMessage()));
        } catch (Exception e) {
            return AF_99Json.toJson(Map.of("success", false, "errorCode", "AFT_INTEGRITY_CHECK", "message", e.getMessage()));
        }
    }

    private String handleBridgeQuote(Map<String, String> params) {
        AF_99Request req = AF_99Request.fromMap(params);
        try {
            String from = params.get("fromChainId");
            String to = params.get("toChainId");
            String amount = params.get("amount");
            if (from == null || to == null || amount == null || req.tokenIn == null) {
                return AF_99Json.toJson(Map.of("success", false, "errorCode", ZynthBridgeCodes.ZB_SOURCE_CHAIN, "message", "Missing params"));
            }
            long fromChainId = Long.parseLong(from);
            long toChainId = Long.parseLong(to);
            BigInteger amountBi = new BigInteger(amount);
            ZynthBridgeQuote q = core.getBridgeQuote(fromChainId, toChainId, req.tokenIn, amountBi);
            Map<String, Object> data = new HashMap<>();
            data.put("quoteId", q.quoteId);
            data.put("fromChainId", q.fromChainId);
            data.put("toChainId", q.toChainId);
            data.put("token", q.token);
            data.put("amount", q.amount.toString());
            data.put("estimatedReceived", q.estimatedReceived.toString());
            data.put("relayFee", q.relayFee.toString());
            data.put("validUntilMs", q.validUntilMs);
            return AF_99Json.toJson(Map.of("success", true, "data", data));
        } catch (ZynthBridgeException e) {
            return AF_99Json.toJson(Map.of("success", false, "errorCode", e.getBridgeCode(), "message", e.getMessage()));
        } catch (Exception e) {
            return AF_99Json.toJson(Map.of("success", false, "errorCode", "ZB_RELAY_BUSY", "message", e.getMessage()));
        }
    }

    private String handleBridgeInit(Map<String, String> params) {
        AF_99Request req = AF_99Request.fromMap(params);
        try {
            if (req.quoteId == null || req.recipient == null) {
                return AF_99Json.toJson(Map.of("success", false, "errorCode", ZynthBridgeCodes.ZB_NONCE_COLLISION, "message", "Missing quoteId or recipient"));
            }
            BridgeInitiatedEvent ev = core.initiateBridge(req.quoteId, req.recipient);
            Map<String, Object> data = new HashMap<>();
            data.put("bridgeId", ev.bridgeId);
            data.put("fromChainId", ev.fromChainId);
            data.put("toChainId", ev.toChainId);
            data.put("token", ev.token);
            data.put("amount", ev.amount.toString());
            data.put("recipient", ev.recipient);
            data.put("timestamp", ev.timestamp.toString());
            return AF_99Json.toJson(Map.of("success", true, "data", data));
        } catch (ZynthBridgeException e) {
            return AF_99Json.toJson(Map.of("success", false, "errorCode", e.getBridgeCode(), "message", e.getMessage()));
        } catch (Exception e) {
            return AF_99Json.toJson(Map.of("success", false, "errorCode", "ZB_RELAY_BUSY", "message", e.getMessage()));
        }
    }

    private String handleStatus() {
        Map<String, Object> data = new HashMap<>();
        data.put("platform", "AftermathXS");
        data.put("app", AF_99Config.APP_NAME);
        data.put("version", core.getPlatformVersion());
        data.put("paused", core.getAggregator().isPaused());
        data.put("chains", List.of(AftChainIds.SUI_NETWORK_NAME, AftChainIds.SOLANA_NETWORK_NAME));
        return AF_99Json.toJson(Map.of("success", true, "data", data));
    }

    private String handleHealth() {
        return AF_99Json.toJson(Map.of("ok", true, "ts", System.currentTimeMillis()));
    }
}

final class AF_99Server {
    private final ServerSocket serverSocket;
    private final AftermathXSCore core;
    private final AF_99QuoteCache cache;
    private final AF_99HttpHandler handler;
    private volatile boolean running = true;

    AF_99Server(int port) throws Exception {
        this.serverSocket = new ServerSocket(port);
        this.core = new AftermathXSCore();
        this.cache = new AF_99QuoteCache(AF_99Config.CACHE_TTL_MS, AF_99Config.MAX_QUOTE_CACHE);
        this.handler = new AF_99HttpHandler(core, cache);
    }

    void start() {
        System.out.println(AF_99Config.APP_NAME + " listening on port " + serverSocket.getLocalPort());
        while (running) {
            try {
                Socket client = serverSocket.accept();
                Thread t = new Thread(() -> serve(client));
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
        }
    }

    void stop() {
        running = false;
        try { serverSocket.close(); } catch (IOException ignored) { }
    }

    private void serve(Socket client) {
        try (Socket c = client;
             BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream()));
             OutputStream out = c.getOutputStream()) {
            String first = in.readLine();
            if (first == null) return;
            String[] parts = first.split("\\s+");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";
            Map<String, String> query = parseQuery(path);
            StringBuilder body = new StringBuilder();
            while (in.ready()) {
                int ch = in.read();
                if (ch < 0) break;
                body.append((char) ch);
            }
            String response = handler.handle(path, query, body.toString());
            String http = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n" + response;
            out.write(http.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Map<String, String> parseQuery(String path) {
        Map<String, String> m = new HashMap<>();
        int q = path.indexOf('?');
        if (q < 0) return m;
        String qs = path.substring(q + 1);
        for (String pair : qs.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = pair.substring(0, eq);
                String v = pair.substring(eq + 1);
                try {
                    m.put(k, java.net.URLDecoder.decode(v, "UTF-8"));
                } catch (Exception ignored) { }
            }
        }
        return m;
    }
}

// -----------------------------------------------------------------------------
// MAIN ENTRY (AF_99)
// -----------------------------------------------------------------------------

public final class AF_99 {
    public static void main(String[] args) {
        int port = AF_99Config.DEFAULT_HTTP_PORT;
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                try { port = Integer.parseInt(args[i + 1]); } catch (NumberFormatException ignored) { }
                break;
            }
        }
        try {
            AF_99Server server = new AF_99Server(port);
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            server.start();
        } catch (Exception e) {
            System.err.println("AF_99 failed to start: " + e.getMessage());
            System.exit(1);
        }
    }
}

// =============================================================================
// AF_99 EXTENDED: SIMULATION & BATCH ENGINE
// =============================================================================

final class AF_99SimulationRequest {
    final long chainId;
    final String tokenIn;
    final String tokenOut;
    final BigInteger amountIn;
    final int stepsMax;

    AF_99SimulationRequest(long chainId, String tokenIn, String tokenOut, BigInteger amountIn, int stepsMax) {
        this.chainId = chainId;
        this.tokenIn = tokenIn;
        this.tokenOut = tokenOut;
        this.amountIn = amountIn;
        this.stepsMax = stepsMax <= 0 ? 3 : stepsMax;
    }
}

final class AF_99SimulationResult {
    final List<AftRouteStep> steps;
    final BigInteger totalOut;
    final BigInteger totalFee;
    final long durationMs;

    AF_99SimulationResult(List<AftRouteStep> steps, BigInteger totalOut, BigInteger totalFee, long durationMs) {
        this.steps = steps == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(steps));
        this.totalOut = totalOut == null ? BigInteger.ZERO : totalOut;
        this.totalFee = totalFee == null ? BigInteger.ZERO : totalFee;
        this.durationMs = durationMs;
    }
}

final class AF_99SimulationEngine {
    private final AftermathXSCore core;

    AF_99SimulationEngine(AftermathXSCore core) {
        this.core = core;
    }

    AF_99SimulationResult run(AF_99SimulationRequest req) {
        long start = System.currentTimeMillis();
        try {
            AftQuoteResult quote = core.quoteSwap(req.chainId, req.tokenIn, req.tokenOut, req.amountIn, BigInteger.ZERO, 0);
            long end = System.currentTimeMillis();
            return new AF_99SimulationResult(quote.steps, quote.amountOut, quote.feeAmount, end - start);
        } catch (Exception e) {
            long end = System.currentTimeMillis();
            return new AF_99SimulationResult(List.of(), BigInteger.ZERO, BigInteger.ZERO, end - start);
        }
    }
}

final class AF_99BatchQuoteItem {
    final String id;
    final long chainId;
    final String tokenIn;
    final String tokenOut;
    final BigInteger amountIn;

    AF_99BatchQuoteItem(String id, long chainId, String tokenIn, String tokenOut, BigInteger amountIn) {
        this.id = id;
        this.chainId = chainId;
        this.tokenIn = tokenIn;
        this.tokenOut = tokenOut;
        this.amountIn = amountIn;
    }
}

final class AF_99BatchQuoteResult {
    final String id;
    final AftQuoteResult quote;
    final String error;

    AF_99BatchQuoteResult(String id, AftQuoteResult quote, String error) {
        this.id = id;
        this.quote = quote;
        this.error = error;
    }
}

final class AF_99BatchProcessor {
    private final AftermathXSCore core;
    private final ExecutorService executor;
    private static final int MAX_CONCURRENCY = 8;

    AF_99BatchProcessor(AftermathXSCore core) {
        this.core = core;
        this.executor = Executors.newFixedThreadPool(MAX_CONCURRENCY);
    }

    List<AF_99BatchQuoteResult> quoteBatch(List<AF_99BatchQuoteItem> items) {
        if (items == null || items.isEmpty()) return List.of();
        List<CompletableFuture<AF_99BatchQuoteResult>> futures = items.stream()
            .map(item -> CompletableFuture.supplyAsync(() -> {
                try {
                    AftQuoteResult q = core.quoteSwap(item.chainId, item.tokenIn, item.tokenOut, item.amountIn, BigInteger.ZERO, 0);
                    return new AF_99BatchQuoteResult(item.id, q, null);
                } catch (Exception e) {
                    return new AF_99BatchQuoteResult(item.id, null, e.getMessage());
                }
            }, executor))
            .collect(Collectors.toList());
        return futures.stream().map(CompletableFuture::join).collect(Collectors.toList());
    }

    void shutdown() {
        executor.shutdown();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

// -----------------------------------------------------------------------------
// METRICS COLLECTOR
// -----------------------------------------------------------------------------

final class AF_99Metrics {
    private final AtomicLong swapCount = new AtomicLong(0);
    private final AtomicLong bridgeCount = new AtomicLong(0);
    private final AtomicLong quoteCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    void recordSwap() { swapCount.incrementAndGet(); }
    void recordBridge() { bridgeCount.incrementAndGet(); }
    void recordQuote() { quoteCount.incrementAndGet(); }
    void recordError() { errorCount.incrementAndGet(); }

    Map<String, Object> snapshot() {
        Map<String, Object> m = new HashMap<>();
        m.put("swaps", swapCount.get());
        m.put("bridges", bridgeCount.get());
        m.put("quotes", quoteCount.get());
        m.put("errors", errorCount.get());
        m.put("ts", System.currentTimeMillis());
        return m;
    }
}

// -----------------------------------------------------------------------------
// ADDITIONAL POOL SEEDS (extended liquidity representation)
// -----------------------------------------------------------------------------

final class AftPoolSeeder {
    static void seedSuiPools(AftermathXSAggregator agg) {
        String[] tokens = {
            "0x2a1B3c4D5e6F7a8B9c0D1e2F3a4B5c6D7e8F9a1",
            "0x4b5C6d7E8f9A0b1C2d3E4f5A6b7C8d9E0f1A2b3",
            "0x6c7D8e9F0a1B2c3D4e5F6a7B8c9D0e1F2a3B4c5",
            "0x8d9E0f1A2b3C4d5E6f7A8b9C0d1E2f3A4b5C6d7",
            "0x0e1F2a3B4c5D6e7F8a9B0c1D2e3F4a5B6c7D8e9",
            "0xF1a2B3c4D5e6F7a8B9c0D1e2F3a4B5c6D7e8F9"
        };
        BigInteger[] reserves = {
            new BigInteger("1500000000000000000000"),
            new BigInteger("2800000000000000000000"),
            new BigInteger("900000000000000000000"),
            new BigInteger("3200000000000000000000"),
            new BigInteger("1100000000000000000000"),
            new BigInteger("700000000000000000000")
        };
        for (int i = 0; i < tokens.length - 1; i++) {
            for (int j = i + 1; j < Math.min(i + 3, tokens.length); j++) {
                String pid = "sui-ext-" + i + "-" + j;
                agg.registerPool(new AftPoolInfo(pid, tokens[i], tokens[j], reserves[i], reserves[j], AftChainIds.CHAIN_SUI, "KrelvexSui"));
            }
        }
    }

    static void seedSolanaPools(AftermathXSAggregator agg) {
        String[] tokens = {
            "0x8d9E0f1A2b3C4d5E6f7A8b9C0d1E2f3A4b5C6d7",
            "0x0e1F2a3B4c5D6e7F8a9B0c1D2e3F4a5B6c7D8e9",
            "0xF1a2B3c4D5e6F7a8B9c0D1e2F3a4B5c6D7e8F9",
            "0x1b2C3d4E5f6A7b8C9d0E1f2A3b4C5d6E7f8A9b0",
            "0x3c4D5e6F7a8B9c0D1e2F3a4B5c6D7e8F9a0B1c2"
        };
        BigInteger[] reserves = {
            new BigInteger("2200000000000000000000"),
            new BigInteger("1800000000000000000000"),
            new BigInteger("600000000000000000000"),
            new BigInteger("4100000000000000000000"),
            new BigInteger("950000000000000000000")
        };
        for (int i = 0; i < tokens.length - 1; i++) {
            String pid = "sol-ext-" + i + "-" + (i + 1);
            agg.registerPool(new AftPoolInfo(pid, tokens[i], tokens[i + 1], reserves[i], reserves[i + 1], AftChainIds.CHAIN_SOLANA, "ZynthSol"));
        }
    }
}

// -----------------------------------------------------------------------------
// ROUTE VALIDATOR (slippage and deadline checks)
// -----------------------------------------------------------------------------

final class AF_99RouteValidator {
    static void validateQuote(AftQuoteResult quote, BigInteger minOut, long deadlineMs) {
        if (quote == null) throw new KrelvexRouteException(AftErrorCodes.AFT_INVALID_ROUTE, "Null quote");
        if (System.currentTimeMillis() > quote.validUntilMs)
            throw new KrelvexRouteException(AftErrorCodes.AFT_ROUTE_STALE, "Quote expired");
        if (minOut != null && minOut.signum() > 0 && quote.amountOut.compareTo(minOut) < 0)
            throw new KrelvexRouteException(AftErrorCodes.AFT_SLIPPAGE_EXCEEDED, "Below minOut");
        if (deadlineMs > 0 && System.currentTimeMillis() > deadlineMs)
            throw new KrelvexRouteException(AftErrorCodes.AFT_DEADLINE_PASSED, "Deadline passed");
    }
}

// -----------------------------------------------------------------------------
// CONFIG LOADER (file-based optional config)
// -----------------------------------------------------------------------------

final class AF_99ConfigLoader {
    static Map<String, String> loadFromEnv() {
        Map<String, String> m = new HashMap<>();
        String port = System.getenv("AF_99_PORT");
        if (port != null) m.put("port", port);
        String host = System.getenv("AF_99_HOST");
        if (host != null) m.put("host", host);
        String cacheSize = System.getenv("AF_99_CACHE_SIZE");
        if (cacheSize != null) m.put("cacheSize", cacheSize);
        String cacheTtl = System.getenv("AF_99_CACHE_TTL_MS");
        if (cacheTtl != null) m.put("cacheTtlMs", cacheTtl);
        return m;
    }

    static int getPort(Map<String, String> env) {
        String p = env.get("port");
        if (p == null) return AF_99Config.DEFAULT_HTTP_PORT;
        try { return Integer.parseInt(p); } catch (NumberFormatException e) { return AF_99Config.DEFAULT_HTTP_PORT; }
    }
}

// -----------------------------------------------------------------------------
// LOGGING ADAPTER (simple stdout)
// -----------------------------------------------------------------------------

final class AF_99Log {
    static void info(String msg) {
        System.out.println("[AF_99 INFO] " + Instant.now() + " " + msg);
    }
    static void warn(String msg) {
        System.err.println("[AF_99 WARN] " + Instant.now() + " " + msg);
    }
    static void error(String msg, Throwable t) {
        System.err.println("[AF_99 ERROR] " + Instant.now() + " " + msg);
        if (t != null) t.printStackTrace(System.err);
    }
}

// -----------------------------------------------------------------------------
// EXTRA ADDRESS CONSTANTS (platform reserves - immutable)
// -----------------------------------------------------------------------------

final class AftReserveAddresses {
    static final String SUI_USDC_POOL = "0x8F3a2B1c4D5e6f7A8b9C0d1E2f3A4b5C6d7E8f9";
    static final String SUI_USDT_POOL = "0x1a2B3c4D5e6F7a8B9c0D1e2F3a4B5c6D7e8F9a0";
    static final String SOL_USDC_POOL = "0x3b4C5d6E7f8A9b0C1d2E3f4A5b6C7d8E9f0A1b2";
    static final String RELAY_SUI_IN = "0x5c6D7e8F9a0B1c2D3e4F5a6b7C8d9E0f1A2b3C4";
    static final String RELAY_SOL_IN = "0x7d8E9f0A1b2C3d4E5f6A7b8C9d0E1f2A3b4C5d6";
    static final String FEE_TREASURY = "0x9e0F1a2B3c4D5e6F7a8B9c0D1e2F3a4B5c6D7e8";
}

// -----------------------------------------------------------------------------
// FEE CALCULATOR (immutable fee tiers)
// -----------------------------------------------------------------------------

final class AftFeeTiers {
    static final BigInteger TIER_LOW_BPS = BigInteger.valueOf(15);
    static final BigInteger TIER_MID_BPS = BigInteger.valueOf(30);
    static final BigInteger TIER_HIGH_BPS = BigInteger.valueOf(50);
    static final BigInteger CAP_BPS = BigInteger.valueOf(100);

    static BigInteger computeFee(BigInteger amount, BigInteger bps) {
        if (amount == null || bps == null) return BigInteger.ZERO;
        if (bps.compareTo(CAP_BPS) > 0) bps = CAP_BPS;
        return amount.multiply(bps).divide(BigInteger.valueOf(10_000));
    }
}

// -----------------------------------------------------------------------------
// CHAIN ROUTER REGISTRY
// -----------------------------------------------------------------------------

final class AftChainRouterRegistry {
    private static final Map<Long, String> ROUTER_BY_CHAIN = new HashMap<>();
    static {
        ROUTER_BY_CHAIN.put(AftChainIds.CHAIN_SUI, AftAddresses.ROUTER_SUI);
        ROUTER_BY_CHAIN.put(AftChainIds.CHAIN_SOLANA, AftAddresses.ROUTER_SOLANA_RELAY);
    }

    static String getRouter(long chainId) {
        return ROUTER_BY_CHAIN.get(chainId);
    }

    static boolean isSupported(long chainId) {
        return ROUTER_BY_CHAIN.containsKey(chainId);
    }
}

// -----------------------------------------------------------------------------
// REQUEST ID GENERATOR
// -----------------------------------------------------------------------------

final class AF_99RequestIdGen {
    private static final AtomicLong counter = new AtomicLong(0);

    static String next() {
        long c = counter.incrementAndGet();
        String hex = Long.toHexString(c);
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "AFT-" + hex + "-" + uuid;
    }
}

// -----------------------------------------------------------------------------
// BRIDGE STATUS TRACKER (in-memory)
// -----------------------------------------------------------------------------

final class AF_99BridgeStatus {
    final String bridgeId;
    final String state;
    final long createdAt;
    final long updatedAt;

    AF_99BridgeStatus(String bridgeId, String state, long createdAt, long updatedAt) {
        this.bridgeId = bridgeId;
        this.state = state;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    static final String STATE_PENDING = "PENDING";
    static final String STATE_RELAYED = "RELAYED";
    static final String STATE_COMPLETED = "COMPLETED";
    static final String STATE_FAILED = "FAILED";
}

final class AF_99BridgeTracker {
    private final Map<String, AF_99BridgeStatus> byId = new ConcurrentHashMap<>();

    void record(String bridgeId, String state) {
        long now = System.currentTimeMillis();
        byId.compute(bridgeId, (k, v) -> new AF_99BridgeStatus(k, state, v == null ? now : v.createdAt, now));
    }

    AF_99BridgeStatus get(String bridgeId) {
        return byId.get(bridgeId);
    }
}

// -----------------------------------------------------------------------------
// TOKEN REGISTRY (symbol -> address per chain)
// -----------------------------------------------------------------------------

final class AF_99TokenRegistry {
    private final Map<Long, Map<String, String>> chainToSymbolToAddress = new ConcurrentHashMap<>();

    void register(long chainId, String symbol, String address) {
        if (!AftAddressValidator.isValid(address)) return;
        chainToSymbolToAddress.computeIfAbsent(chainId, k -> new ConcurrentHashMap<>()).put(symbol, address);
    }

    String getAddress(long chainId, String symbol) {
        Map<String, String> m = chainToSymbolToAddress.get(chainId);
        return m == null ? null : m.get(symbol);
    }

    Set<String> getSymbols(long chainId) {
        Map<String, String> m = chainToSymbolToAddress.get(chainId);
        return m == null ? Set.of() : Set.copyOf(m.keySet());
    }
}

// -----------------------------------------------------------------------------
// RATE LIMITER (simple in-memory)
// -----------------------------------------------------------------------------

final class AF_99RateLimiter {
    private final int maxPerWindow;
    private final long windowMs;
    private final Map<String, List<Long>> keyToTimestamps = new ConcurrentHashMap<>();

    AF_99RateLimiter(int maxPerWindow, long windowMs) {
        this.maxPerWindow = maxPerWindow;
        this.windowMs = windowMs;
    }

    boolean allow(String key) {
        long now = System.currentTimeMillis();
        List<Long> list = keyToTimestamps.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        list.removeIf(ts -> now - ts > windowMs);
        if (list.size() >= maxPerWindow) return false;
        list.add(now);
        return true;
    }
}

// -----------------------------------------------------------------------------
// HEALTH CHECK RESULT
// -----------------------------------------------------------------------------

final class AF_99HealthResult {
    final boolean aggregatorOk;
    final boolean bridgeOk;
    final long timestamp;

    AF_99HealthResult(boolean aggregatorOk, boolean bridgeOk, long timestamp) {
        this.aggregatorOk = aggregatorOk;
        this.bridgeOk = bridgeOk;
        this.timestamp = timestamp;
    }
}

// -----------------------------------------------------------------------------
// EXTENDED HTTP HANDLER (metrics, rate limit, simulation, batch)
// -----------------------------------------------------------------------------

final class AF_99ExtendedHandler extends AF_99HttpHandler {
    private final AF_99Metrics metrics;
    private final AF_99RateLimiter rateLimiter;
    private final AF_99SimulationEngine simulationEngine;
    private final AF_99BatchProcessor batchProcessor;

    AF_99ExtendedHandler(AftermathXSCore core, AF_99QuoteCache cache, AF_99Metrics metrics,
                         AF_99RateLimiter rateLimiter, AF_99SimulationEngine simulationEngine,
                         AF_99BatchProcessor batchProcessor) {
        super(core, cache);
        this.metrics = metrics;
        this.rateLimiter = rateLimiter;
        this.simulationEngine = simulationEngine;
        this.batchProcessor = batchProcessor;
    }

    String handleExtended(String path, Map<String, String> queryParams, String body) {
        String clientKey = queryParams.getOrDefault("clientId", "default");
        if (!rateLimiter.allow(clientKey)) {
            return AF_99Json.toJson(Map.of("success", false, "errorCode", "AFT_FEE_CAP_EXCEEDED", "message", "Rate limited"));
        }
        if ("/metrics".equals(path)) {
            return AF_99Json.toJson(Map.of("success", true, "data", metrics.snapshot()));
        }
        if ("/simulate".equals(path)) {
            return handleSimulate(queryParams);
        }
        if ("/batch/quote".equals(path)) {
            return handleBatchQuote(body);
        }
        return handle(path, queryParams, body);
    }

    private String handleSimulate(Map<String, String> params) {
        try {
            String chainIdStr = params.get("chainId");
            String tokenIn = params.get("tokenIn");
            String tokenOut = params.get("tokenOut");
            String amountStr = params.get("amountIn");
            if (chainIdStr == null || tokenIn == null || tokenOut == null || amountStr == null) {
                return AF_99Json.toJson(Map.of("success", false, "message", "Missing params"));
            }
            long chainId = Long.parseLong(chainIdStr);
            BigInteger amountIn = new BigInteger(amountStr);
            AF_99SimulationRequest req = new AF_99SimulationRequest(chainId, tokenIn, tokenOut, amountIn, 3);
            AF_99SimulationResult res = simulationEngine.run(req);
            Map<String, Object> data = new HashMap<>();
            data.put("totalOut", res.totalOut.toString());
            data.put("totalFee", res.totalFee.toString());
            data.put("durationMs", res.durationMs);
            data.put("stepCount", res.steps.size());
            return AF_99Json.toJson(Map.of("success", true, "data", data));
        } catch (Exception e) {
            metrics.recordError();
            return AF_99Json.toJson(Map.of("success", false, "message", e.getMessage()));
        }
    }

    private String handleBatchQuote(String body) {
        try {
            if (body == null || body.isBlank()) {
                return AF_99Json.toJson(Map.of("success", false, "message", "Empty body"));
            }
            List<AF_99BatchQuoteItem> items = parseBatchItems(body);
            if (items.isEmpty()) return AF_99Json.toJson(Map.of("success", false, "message", "No items"));
            List<AF_99BatchQuoteResult> results = batchProcessor.quoteBatch(items);
            List<Map<String, Object>> list = new ArrayList<>();
            for (AF_99BatchQuoteResult r : results) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("id", r.id);
                if (r.quote != null) {
                    entry.put("quoteId", r.quote.quoteId);
                    entry.put("amountOut", r.quote.amountOut.toString());
                }
                if (r.error != null) entry.put("error", r.error);
                list.add(entry);
            }
            return AF_99Json.toJson(Map.of("success", true, "data", list));
        } catch (Exception e) {
            metrics.recordError();
            return AF_99Json.toJson(Map.of("success", false, "message", e.getMessage()));
        }
    }

    private List<AF_99BatchQuoteItem> parseBatchItems(String body) {
        List<AF_99BatchQuoteItem> items = new ArrayList<>();
        String[] lines = body.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < 5) continue;
            try {
                String id = parts[0];
                long chainId = Long.parseLong(parts[1]);
                String tokenIn = parts[2];
                String tokenOut = parts[3];
                BigInteger amountIn = new BigInteger(parts[4]);
                items.add(new AF_99BatchQuoteItem(id, chainId, tokenIn, tokenOut, amountIn));
            } catch (Exception ignored) { }
        }
        return items;
    }
}

// -----------------------------------------------------------------------------
// CLI RUNNER (for testing from command line)
