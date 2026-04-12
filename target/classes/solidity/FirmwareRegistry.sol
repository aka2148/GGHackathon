// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

contract FirmwareRegistry {
    // Maps Charger ID to its authorized Firmware Hash
    mapping(string => string) private goldenHashes;

    // Only the authorized admin should call this in production.
    function registerGoldenHash(string memory chargerId, string memory firmwareHash) public {
        goldenHashes[chargerId] = firmwareHash;
    }

    // Used by the trust module to verify live station hashes.
    function getGoldenHash(string memory chargerId) public view returns (string memory) {
        return goldenHashes[chargerId];
    }
}
