// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

contract FirmwareRegistry {
    address public owner;

    // Maps Charger ID to its authorized Firmware Hash
    mapping(string => string) private goldenHashes;

    event GoldenHashRegistered(string indexed chargerId, string goldenHash, address indexed updatedBy);
    event SessionEventRecorded(string indexed chargerId, string sessionId, string state, address indexed recordedBy);

    modifier onlyOwner() {
        require(msg.sender == owner, "FirmwareRegistry: caller is not the owner");
        _;
    }

    constructor() {
        owner = msg.sender;
    }

    // Only the authorized admin should call this in production.
    function registerGoldenHash(string memory chargerId, string memory firmwareHash) public onlyOwner {
        goldenHashes[chargerId] = firmwareHash;
        emit GoldenHashRegistered(chargerId, firmwareHash, msg.sender);
    }

    // Used by the trust module to verify live station hashes.
    function getGoldenHash(string memory chargerId) public view returns (string memory) {
        return goldenHashes[chargerId];
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
