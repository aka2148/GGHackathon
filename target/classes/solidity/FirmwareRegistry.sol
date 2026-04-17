// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

contract FirmwareRegistry {
    address public owner;

    struct SignedGoldenRecord {
        string goldenHash;
        string manufacturerSignature;
        string manufacturerId;
        uint256 registeredAt;
    }

    // Maps charger ID to its signed golden hash record.
    mapping(string => SignedGoldenRecord) private signedGoldenRecords;

    event GoldenHashRegistered(string indexed chargerId, string goldenHash, address indexed updatedBy);
    event SignedGoldenHashRegistered(
        string indexed chargerId,
        string goldenHash,
        string manufacturerId,
        address indexed updatedBy,
        uint256 registeredAt
    );
    event SessionEventRecorded(string indexed chargerId, string sessionId, string state, address indexed recordedBy);

    modifier onlyOwner() {
        require(msg.sender == owner, "FirmwareRegistry: caller is not the owner");
        _;
    }

    constructor() {
        owner = msg.sender;
    }

    // Legacy registration method: records hash without signature context.
    function registerGoldenHash(string memory chargerId, string memory firmwareHash) public onlyOwner {
        signedGoldenRecords[chargerId] = SignedGoldenRecord({
            goldenHash: firmwareHash,
            manufacturerSignature: "",
            manufacturerId: "",
            registeredAt: block.timestamp
        });
        emit GoldenHashRegistered(chargerId, firmwareHash, msg.sender);
        emit SignedGoldenHashRegistered(chargerId, firmwareHash, "", msg.sender, block.timestamp);
    }

    // New canonical registration method for signed golden records.
    function registerSignedGoldenHash(
        string memory chargerId,
        string memory firmwareHash,
        string memory manufacturerSignature,
        string memory manufacturerId
    ) public onlyOwner {
        signedGoldenRecords[chargerId] = SignedGoldenRecord({
            goldenHash: firmwareHash,
            manufacturerSignature: manufacturerSignature,
            manufacturerId: manufacturerId,
            registeredAt: block.timestamp
        });

        emit GoldenHashRegistered(chargerId, firmwareHash, msg.sender);
        emit SignedGoldenHashRegistered(chargerId, firmwareHash, manufacturerId, msg.sender, block.timestamp);
    }

    // Used by the trust module for backward-compatible hash retrieval.
    function getGoldenHash(string memory chargerId) public view returns (string memory) {
        return signedGoldenRecords[chargerId].goldenHash;
    }

    // Returns the full signed golden record used for signature verification.
    function getSignedGoldenRecord(string memory chargerId)
        public
        view
        returns (string memory, string memory, string memory, uint256)
    {
        SignedGoldenRecord memory record = signedGoldenRecords[chargerId];
        return (
            record.goldenHash,
            record.manufacturerSignature,
            record.manufacturerId,
            record.registeredAt
        );
    }

    // Records operational session activity so it is visible in the chain history.
    function recordSessionEvent(
        string memory chargerId,
        string memory sessionId,
        string memory state
    ) public onlyOwner {
        emit SessionEventRecorded(chargerId, sessionId, state, msg.sender);
    }
}
