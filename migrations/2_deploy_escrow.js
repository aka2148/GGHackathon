const ChargingEscrow = artifacts.require("ChargingEscrow");

module.exports = async function (deployer, network, accounts) {

  const operator = accounts[0];
  const charger  = accounts[1];

  const stationId = "CS-101";

  // IMPORTANT: Proper bytes32 hash
  const goldenHash = web3.utils.keccak256("CS-101");

  const targetSoc = 80;
  const sessionTimeout = 3600;

  console.log("Deploying with:");
  console.log({ operator, charger, stationId, goldenHash, targetSoc, sessionTimeout });

  await deployer.deploy(
    ChargingEscrow,
    operator,
    charger,
    stationId,
    goldenHash,
    targetSoc,
    sessionTimeout
  );
};