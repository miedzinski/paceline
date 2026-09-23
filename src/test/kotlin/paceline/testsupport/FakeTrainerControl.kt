package paceline.testsupport

import paceline.device.ports.TrainerControl

class FakeTrainerControl : TrainerControl {
    var requestControlCalls = 0
        private set
    var freeRideCalls = 0
        private set
    var resistanceReleaseCalls = 0
        private set
    var stopCalls = 0
        private set
    var pauseCalls = 0
        private set
    var startOrResumeCalls = 0
        private set
    val targetPowers = mutableListOf<Int>()
    val targetPowerAttempts = mutableListOf<Int>()
    val powerAndLifecycleCommands = mutableListOf<String>()
    var requestControlFailure: Exception? = null
    var freeRideFailure: Exception? = null
    var targetPowerFailure: Exception? = null
    var resistanceReleaseFailure: Exception? = null
    var stopFailure: Exception? = null
    var pauseFailure: Exception? = null
    var startOrResumeFailure: Exception? = null

    override fun requestControl() {
        requestControlCalls += 1
        requestControlFailure?.let { throw it }
    }

    override fun setTargetPower(powerWatts: Int) {
        powerAndLifecycleCommands += "target:$powerWatts"
        targetPowerAttempts += powerWatts
        targetPowerFailure?.let { throw it }
        targetPowers += powerWatts
    }

    override fun setFreeRide() {
        freeRideCalls += 1
        freeRideFailure?.let { throw it }
    }

    override fun releaseResistance() {
        resistanceReleaseCalls += 1
        resistanceReleaseFailure?.let { throw it }
    }

    override fun stop() {
        powerAndLifecycleCommands += "stop"
        stopCalls += 1
        stopFailure?.let { throw it }
    }

    override fun pause() {
        powerAndLifecycleCommands += "pause"
        pauseCalls += 1
        pauseFailure?.let { throw it }
    }

    override fun startOrResume() {
        startOrResumeCalls += 1
        startOrResumeFailure?.let { throw it }
    }
}
