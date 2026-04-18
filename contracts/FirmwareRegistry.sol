pragma solidity ^0.8.17;

contract FirmwareRegistry {

    struct SignedGoldenRecord {
        string goldenHash;
        string manufacturerSignature;
        string manufacturerId;
    }

    mapping(string => SignedGoldenRecord) private records;

    event GoldenHashRegistered(string stationId, string goldenHash, string manufacturerId);
    event SessionEventRecorded(string stationId, string sessionId, string state);

    function registerGoldenHash(string memory stationId, string memory goldenHash) public {
        records[stationId] = SignedGoldenRecord(goldenHash, "", "");
        emit GoldenHashRegistered(stationId, goldenHash, "");
    }

    function registerSignedGoldenHash(
        string memory stationId,
        string memory goldenHash,
        string memory manufacturerSignature,
        string memory manufacturerId
    ) public {
        records[stationId] = SignedGoldenRecord(goldenHash, manufacturerSignature, manufacturerId);
        emit GoldenHashRegistered(stationId, goldenHash, manufacturerId);
    }

    function getGoldenHash(string memory stationId) public view returns (string memory) {
        return records[stationId].goldenHash;
    }

    function getSignedGoldenRecord(string memory stationId)
        public view returns (SignedGoldenRecord memory) {
        return records[stationId];
    }

    function recordSessionEvent(
        string memory stationId,
        string memory sessionId,
        string memory state
    ) public {
        emit SessionEventRecorded(stationId, sessionId, state);
    }
}
