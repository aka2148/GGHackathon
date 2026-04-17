// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/**
 * ChargingEscrow — payment escrow for a single EV charging session.
 *
 * Lifecycle:
 *   CREATED    → deposit()         → FUNDED
 *   FUNDED     → verifyStation()   → AUTHORIZED  (hash matches)
 *   FUNDED     → refund()          → REFUNDED     (hash mismatch — called by backend)
 *   AUTHORIZED → startCharging()   → CHARGING
 *   CHARGING   → updateSoc()       (no state change — progress tracking only)
 *   CHARGING   → completeSession() → COMPLETED
 *   COMPLETED  → releaseFunds()    → RELEASED
 *   any live   → cancel()          → REFUNDED     (timeout / attack detected by backend)
 *
 * IMPORTANT: Hash verification and SoC logic live off-chain in your Spring backend.
 * This contract only enforces the money rules and records state transitions.
 * The backend calls verifyStation(), updateSoc(), completeSession(), etc.
 * after performing its own checks — the contract trusts the operator (msg.sender checks).
 */
contract ChargingEscrow {

    // ── State machine ─────────────────────────────────────────────────────────

    enum SessionState {
        CREATED,
        FUNDED,
        AUTHORIZED,
        CHARGING,
        COMPLETED,
        RELEASED,
        REFUNDED
    }

    // ── Storage ───────────────────────────────────────────────────────────────

    address public operator;        // the GridGarrison backend wallet (msg.sender for admin calls)
    address payable public buyer;   // the EV driver who deposited funds
    address payable public charger; // the charging station wallet that receives payment

    uint256 public amount;          // deposited amount in wei
    bytes32 public goldenHash;      // authoritative hash registered at deploy time
    SessionState public state;

    string  public stationId;       // human-readable station ID for logging
    string  public sessionId;       // session identifier set at startCharging

    uint8   public targetSoc;       // user-defined charge target (0-100)
    uint8   public currentSoc;      // latest SoC reported by backend

    uint256 public createdAt;
    uint256 public sessionTimeout;  // seconds after CHARGING starts before cancel() is callable

    // ── Events ────────────────────────────────────────────────────────────────

    event Deposited(address indexed buyer, uint256 amount);
    event StationVerified(string stationId, bytes32 liveHash);
    event VerificationFailed(string stationId, bytes32 liveHash, bytes32 goldenHash);
    event ChargingStarted(string sessionId, uint8 targetSoc);
    event SocUpdated(uint8 previousSoc, uint8 currentSoc);
    event SessionCompleted(string sessionId, uint8 finalSoc);
    event FundsReleased(address indexed charger, uint256 amount);
    event Refunded(address indexed buyer, uint256 amount, string reason);
    event Cancelled(string reason);

    // ── Modifiers ─────────────────────────────────────────────────────────────

    modifier onlyOperator() {
        require(msg.sender == operator, "ChargingEscrow: caller is not operator");
        _;
    }

    modifier inState(SessionState expected) {
        require(state == expected, "ChargingEscrow: wrong session state");
        _;
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
 * @param _operator
 * @param _charger
 * @param _stationId
 * @param _goldenHash
 * @param _targetSoc
 * @param _sessionTimeout
 */
    constructor(
    address _operator,
    address payable _charger,
    string memory _stationId,
    bytes32 _goldenHash,
    uint8 _targetSoc,
    uint256 _sessionTimeout
) {
    require(_operator != address(0), "operator required");
    require(_charger  != address(0), "charger required");
    require(_targetSoc > 0 && _targetSoc <= 100, "targetSoc must be 1-100");

    operator       = _operator;
    charger        = _charger;
    stationId      = _stationId;
    goldenHash     = _goldenHash;
    targetSoc      = _targetSoc;
    sessionTimeout = _sessionTimeout;
    createdAt      = block.timestamp;
    state          = SessionState.CREATED;
}

    // ── Buyer functions ───────────────────────────────────────────────────────

    /**
     * EV driver deposits payment into escrow.
     * Must be called with msg.value > 0 while state is CREATED.
     * Transitions: CREATED → FUNDED
     */
    function deposit() external payable inState(SessionState.CREATED) {
        require(msg.value > 0, "ChargingEscrow: deposit must be > 0");
        buyer  = payable(msg.sender);
        amount = msg.value;
        state  = SessionState.FUNDED;
        emit Deposited(msg.sender, msg.value);
    }

    // ── Operator functions (called by GridGarrison Spring backend) ────────────

    /**
     * Backend calls this after verifying the live hash off-chain against
     * FirmwareRegistry. If hashes match, session moves to AUTHORIZED.
     * If they don't match, backend should call refund() instead.
     *
     * The liveHash parameter is stored for on-chain auditability — the actual
     * comparison logic lives in your BlockchainServiceImpl / DigitalTwinServiceImpl.
     *
     * @param liveHash  The hash the station reported (0x-prefixed, sent as bytes32)
     * Transitions: FUNDED → AUTHORIZED
     */
    function verifyStation(bytes32 liveHash)
        external
        onlyOperator
        inState(SessionState.FUNDED)
    {
        if (liveHash == goldenHash) {
            state = SessionState.AUTHORIZED;
            emit StationVerified(stationId, liveHash);
        } else {
            // Backend should call refund() — emit event so off-chain indexer sees it
            emit VerificationFailed(stationId, liveHash, goldenHash);
            // Do NOT revert — let operator decide whether to refund or retry
        }
    }

    /**
     * Backend calls this when the OCPP transaction START event is received
     * and the session is AUTHORIZED.
     *
     * @param _sessionId  OCPP transaction ID / session UUID
     * Transitions: AUTHORIZED → CHARGING
     */
    function startCharging(string calldata _sessionId)
        external
        onlyOperator
        inState(SessionState.AUTHORIZED)
    {
        sessionId  = _sessionId;
        currentSoc = 0;
        state      = SessionState.CHARGING;
        emit ChargingStarted(_sessionId, targetSoc);
    }

    /**
     * Backend calls this periodically as MeterValues arrive over OCPP.
     * No state transition — just updates currentSoc for on-chain auditability.
     *
     * @param soc  Current state-of-charge reported by station (0–100)
     */
    function updateSoc(uint8 soc)
        external
        onlyOperator
        inState(SessionState.CHARGING)
    {
        require(soc <= 100, "ChargingEscrow: SoC out of range");
        uint8 previous = currentSoc;
        currentSoc = soc;
        emit SocUpdated(previous, soc);
    }

    /**
     * Backend calls this when targetSoc is reached OR when the OCPP
     * TransactionEvent END is received.
     * Transitions: CHARGING → COMPLETED
     */
    function completeSession()
        external
        onlyOperator
        inState(SessionState.CHARGING)
    {
        state = SessionState.COMPLETED;
        emit SessionCompleted(sessionId, currentSoc);
    }

    /**
     * Releases escrowed funds to the charger after session completes successfully.
     * Transitions: COMPLETED → RELEASED
     */
    function releaseFunds()
        external
        onlyOperator
        inState(SessionState.COMPLETED)
    {
        state = SessionState.RELEASED;
        uint256 payout = amount;
        amount = 0;
        charger.transfer(payout);
        emit FundsReleased(charger, payout);
    }

    /**
     * Refunds the buyer. Called by the backend when:
     *  - hash verification fails (TAMPERED / UNKNOWN_STATION)
     *  - anomaly detected by DigitalTwinServiceImpl (ALERT state)
     * Can be called from FUNDED, AUTHORIZED, or CHARGING states.
     *
     * @param reason  Short human-readable reason string (stored in event log)
     */
    function refund(string calldata reason) external onlyOperator {
        require(
            state == SessionState.FUNDED     ||
            state == SessionState.AUTHORIZED ||
            state == SessionState.CHARGING,
            "ChargingEscrow: cannot refund in current state"
        );
        state = SessionState.REFUNDED;
        uint256 payout = amount;
        amount = 0;
        buyer.transfer(payout);
        emit Refunded(buyer, payout, reason);
    }

    /**
     * Emergency cancel — callable by operator if session times out or
     * a critical anomaly is detected after CHARGING begins.
     * Also callable by buyer after sessionTimeout elapses (trustless fallback).
     */
    function cancel(string calldata reason) external {
        require(
            msg.sender == operator || msg.sender == buyer,
            "ChargingEscrow: not authorised"
        );

        // Buyer self-cancel only allowed after timeout
        if (msg.sender == buyer) {
            require(
                state == SessionState.CHARGING &&
                block.timestamp >= createdAt + sessionTimeout,
                "ChargingEscrow: timeout not elapsed"
            );
        }

        require(
            state != SessionState.RELEASED &&
            state != SessionState.REFUNDED,
            "ChargingEscrow: already finalised"
        );

        emit Cancelled(reason);
        state = SessionState.REFUNDED;
        uint256 payout = amount;
        amount = 0;
        if (payout > 0) {
            buyer.transfer(payout);
            emit Refunded(buyer, payout, reason);
        }
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    function getState() external view returns (SessionState) { return state; }
    function getBalance() external view returns (uint256)    { return address(this).balance; }
}
