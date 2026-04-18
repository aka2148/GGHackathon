// SPDX-License-Identifier: MIT
pragma solidity ^0.8.17;

contract ChargingEscrow {

    enum SessionState {
        CREATED,
        FUNDED,
        AUTHORIZED,
        CHARGING,
        COMPLETED,
        RELEASED,
        REFUNDED
    }

    address public operator;
    address payable public buyer;
    address payable public charger;

    uint256 public amount;
    bytes32 public goldenHash;
    SessionState public state;

    string public stationId;
    string public sessionId;

    uint8 public targetSoc;
    uint8 public currentSoc;

    uint256 public createdAt;
    uint256 public sessionTimeout;

    event Deposited(address indexed buyer, uint256 amount);
    event StationVerified(string stationId, bytes32 liveHash);
    event VerificationFailed(string stationId, bytes32 liveHash, bytes32 goldenHash);
    event ChargingStarted(string sessionId, uint8 targetSoc);
    event SocUpdated(uint8 previousSoc, uint8 currentSoc);
    event SessionCompleted(string sessionId, uint8 finalSoc);
    event FundsReleased(address indexed charger, uint256 amount);
    event Refunded(address indexed buyer, uint256 amount, string reason);
    event Cancelled(string reason);

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
     * @param _operator Backend operator wallet address that is allowed to run admin transitions.
     * @param _charger Wallet address that receives the released payment after successful charging.
     * @param _stationId Human-readable station identifier used for events and audit context.
     * @param _goldenHash Canonical firmware hash baseline stored for station verification.
     * @param _targetSoc Requested charging target percentage in the range 1 to 100.
     * @param _sessionTimeout Timeout in seconds used for buyer self-cancel protection.
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

    function deposit() external payable inState(SessionState.CREATED) {
        require(msg.value > 0, "ChargingEscrow: deposit must be > 0");
        buyer  = payable(msg.sender);
        amount = msg.value;
        state  = SessionState.FUNDED;
        emit Deposited(msg.sender, msg.value);
    }

    function verifyStation(bytes32 liveHash)
        external
        onlyOperator
        inState(SessionState.FUNDED)
    {
        if (liveHash == goldenHash) {
            state = SessionState.AUTHORIZED;
            emit StationVerified(stationId, liveHash);
        } else {
            emit VerificationFailed(stationId, liveHash, goldenHash);
        }
    }

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

    function completeSession()
        external
        onlyOperator
        inState(SessionState.CHARGING)
    {
        state = SessionState.COMPLETED;
        emit SessionCompleted(sessionId, currentSoc);
    }

    function releaseFunds()
        external
        onlyOperator
        inState(SessionState.COMPLETED)
    {
        state = SessionState.RELEASED;
        uint256 payout = amount;
        amount = 0;

        (bool success, ) = charger.call{value: payout}("");
        require(success, "Transfer to charger failed");

        emit FundsReleased(charger, payout);
    }

    function refund(string calldata reason) external onlyOperator {
        require(
            state == SessionState.FUNDED ||
            state == SessionState.AUTHORIZED ||
            state == SessionState.CHARGING,
            "ChargingEscrow: cannot refund in current state"
        );

        state = SessionState.REFUNDED;
        uint256 payout = amount;
        amount = 0;

        (bool success, ) = buyer.call{value: payout}("");
        require(success, "Refund to buyer failed");

        emit Refunded(buyer, payout, reason);
    }

    function cancel(string calldata reason) external {
        require(
            msg.sender == operator || msg.sender == buyer,
            "ChargingEscrow: not authorised"
        );

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
            (bool success, ) = buyer.call{value: payout}("");
            require(success, "Refund failed");

            emit Refunded(buyer, payout, reason);
        }
    }

    function getState() external view returns (SessionState) {
        return state;
    }

    function getBalance() external view returns (uint256) {
        return address(this).balance;
    }
}