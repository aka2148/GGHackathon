const ChargingEscrow = artifacts.require("ChargingEscrow");

module.exports = async function (deployer, network, accounts) {

  const operator = accounts[0];
  const charger  = accounts[1];

  const stationId = "CS-101";

  // SAFE bytes32
  const goldenHash = web3.utils.keccak256("CS-101");

  const targetSoc = 80;
  const timeout   = 3600;

  await deployer.deploy(
  ChargingEscrow,
  operator,
  charger,
  stationId,
  goldenHash,
  targetSoc,
  timeout
);
};