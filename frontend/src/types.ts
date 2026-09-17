export type DeviceTransport = "WIFI" | "BLUETOOTH";

export interface AthleteProfile {
    athleteId: string | null;
    name: string | null;
    ftpWatts: number | null;
    indoorFtpWatts: number | null;
    powerZones: PowerZone[];
}

export interface PowerZone {
    number: number;
    name: string;
    minPercent: number;
    maxPercent: number | null;
    minWatts: number;
    maxWatts: number | null;
}

export interface DeviceEndpoint {
    transport: DeviceTransport;
    host: string | null;
    port: number | null;
    address: string | null;
}

export interface Device {
    id: string;
    name: string;
    transport: DeviceTransport;
    host: string | null;
    port: number | null;
    address: string | null;
}

export interface DeviceFailure {
    code: string;
    message: string;
}

export interface DeviceDiscoveryResponse {
    state: string;
    changedAt: string;
    devices: Device[];
    failure: DeviceFailure | null;
}

export interface ConnectedDevice extends DeviceEndpoint {
    name: string;
    id?: string | null;
}

export interface IndoorBikeTelemetry {
    powerWatts: number | null;
    cadenceRpm: number | null;
    speedKph: number | null;
    receivedAt: string;
}

export interface DeviceHeartRate {
    heartRateBpm: number;
    receivedAt: string;
}

export interface ConnectedConnection {
    id: string;
    state: string;
    changedAt: string;
    device: ConnectedDevice;
    failure: DeviceFailure | null;
    telemetry: IndoorBikeTelemetry | null;
    heartRate: DeviceHeartRate | null;
    capabilities: string[];
}

export interface HeartRateSource {
    id: string;
    device: ConnectedDevice;
    state: string;
    heartRate: DeviceHeartRate | null;
}

export interface DeviceConnectionResponse {
    state: string;
    changedAt: string;
    device: ConnectedDevice | null;
    failure: DeviceFailure | null;
    telemetry: IndoorBikeTelemetry | null;
    connections: ConnectedConnection[];
    heartRateSources: HeartRateSource[];
}

export interface TrainingSessionResponse {
    state: TrainingSessionState;
    sessionId: string | null;
    startedAt: string | null;
    changedAt: string;
    controlMode: TrainingControlMode;
    ergRequestedTargetPowerWatts: number | null;
    ergTargetPowerWatts: number | null;
    workoutPowerTargetPercent: number | null;
    ergProtection: ErgProtectionResponse;
    trainerConnection: TrainerConnectionStatus;
    trainerConnectionRetryAttempt: number | null;
    trainerConnectionError: string | null;
    heartRateSourceId: string | null;
    heartRate: DeviceHeartRate | null;
    workout: TrainingWorkoutResponse | null;
    activityUpload: TrainingActivityUploadResponse;
}

export type TrainingControlMode = "ERG" | "FREE_RIDE";

export type TrainerConnectionStatus =
    "NOT_ACTIVE" | "CONNECTED" | "INTERRUPTED" | "RECONNECTING";

export type ErgProtectionState =
    | "INACTIVE"
    | "BAILED_OUT"
    | "RECOVERY_RETRYING"
    | "RECOVERY_FAILED"
    | "UNAVAILABLE";

export interface ErgProtectionResponse {
    state: ErgProtectionState;
    changedAt: string | null;
    cadenceRpm: number | null;
    error: string | null;
    retryAttempt: number | null;
    nextRetryAt: string | null;
}

export type TrainingSessionState =
    "NOT_STARTED" | "ACTIVE" | "PAUSED" | "STOPPED" | "COMPLETED";

export type ActivityUploadState =
    "UNAVAILABLE" | "AVAILABLE" | "UPLOADING" | "FAILED";

export interface TrainingActivityUploadResponse {
    state: ActivityUploadState;
    remoteActivityId: string | null;
    error: string | null;
}

export interface TrainingWorkoutResponse {
    provider: string;
    sourceId: string;
    name: string;
    currentStep: number;
    totalSteps: number;
    stepText: string | null;
    stepStartedAt: string;
    completion: TrainingStepCompletionResponse;
    target: TrainingStepTargetResponse;
    completed: boolean;
}

export interface TrainingStepCompletionResponse {
    kind: "TIME" | "DISTANCE" | "MANUAL";
    value: number | null;
}

export interface TrainingStepTargetResponse {
    kind: "POWER" | "RAMP" | "OPEN";
    lowWatts: number | null;
    highWatts: number | null;
    startWatts: number | null;
    endWatts: number | null;
}

export interface WorkoutSelection {
    provider: string;
    sourceType: "SCHEDULED" | "LIBRARY";
    sourceId: string;
}

export interface WorkoutTarget {
    value: number | null;
    start: number | null;
    end: number | null;
    units: string | null;
    target: string | null;
}

export interface WorkoutStep {
    text: string | null;
    durationSeconds: number | null;
    distanceMeters: number | null;
    repeats: number | null;
    warmup: boolean | null;
    cooldown: boolean | null;
    intensity: string | null;
    ramp: boolean | null;
    freeRide: boolean | null;
    power: WorkoutTarget | null;
    resolvedPower: WorkoutTarget | null;
    heartRate: WorkoutTarget | null;
    pace: WorkoutTarget | null;
    cadence: WorkoutTarget | null;
    steps: WorkoutStep[];
}

export interface WorkoutDefinition {
    description: string | null;
    durationSeconds: number | null;
    distanceMeters: number | null;
    ftpWatts: number | null;
    thresholdHeartRateBpm: number | null;
    target: string | null;
    steps: WorkoutStep[];
}

export interface WorkoutZoneDistribution {
    zone: string;
    durationSeconds: number;
}

export interface ScheduledWorkout {
    provider: string;
    sourceEventId: string;
    name: string | null;
    description: string | null;
    type: string | null;
    startAt: string | null;
    endAt: string | null;
    indoor: boolean | null;
    durationSeconds: number | null;
    distanceMeters: number | null;
    trainingLoad: number | null;
    plannedZoneDistribution: WorkoutZoneDistribution[] | null;
    target: string | null;
    workout: WorkoutDefinition | null;
}

export interface LibraryWorkout {
    provider: string;
    sourceWorkoutId: string;
    name: string | null;
    description: string | null;
    type: string | null;
    indoor: boolean | null;
    durationSeconds: number | null;
    distanceMeters: number | null;
    trainingLoad: number | null;
    plannedZoneDistribution: WorkoutZoneDistribution[] | null;
    intensity: number | null;
    target: string | null;
    targets: string[];
    folderId: number | null;
    workout: WorkoutDefinition | null;
}

export interface TodayWorkoutsResponse {
    date: string;
    scheduledWorkouts: ScheduledWorkout[];
    fetchedAt: string;
}

export interface WorkoutLibraryResponse {
    workouts: LibraryWorkout[];
    fetchedAt: string;
}
