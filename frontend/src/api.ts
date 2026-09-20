import type {
    AthleteProfile,
    DeviceConnectionsResponse,
    DeviceDiscoveryResponse,
    RideEquipmentResponse,
    RideEquipmentSelection,
    RideRole,
    TodayWorkoutsResponse,
    TrainingSessionResponse,
    WorkoutSelection,
    WorkoutLibraryResponse,
} from "./types";

const apiBaseUrl = (
    import.meta.env.VITE_API_BASE_URL?.trim() || "/api"
).replace(/\/$/, "");

export class ApiError extends Error {
    constructor(
        message: string,
        readonly status: number,
        readonly code: string | null = null,
    ) {
        super(message);
        this.name = "ApiError";
    }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await fetch(`${apiBaseUrl}${path}`, {
        ...init,
        headers: {
            Accept: "application/json",
            ...init?.headers,
        },
    });
    const body = await response.text();
    const parsedBody = parseBody(body);

    if (!response.ok) {
        const details = errorDetails(parsedBody, response.status);
        throw new ApiError(details.message, response.status, details.code);
    }

    if (typeof parsedBody === "string") {
        throw new ApiError(
            "The server returned an invalid response",
            response.status,
        );
    }

    return parsedBody as T;
}

function parseBody(body: string): unknown {
    if (body.length === 0) {
        return null;
    }

    try {
        return JSON.parse(body);
    } catch {
        return body;
    }
}

function errorDetails(
    body: unknown,
    status: number,
): { code: string | null; message: string } {
    if (typeof body === "object" && body !== null) {
        const bodyRecord = body as Record<string, unknown>;
        const code =
            typeof bodyRecord.code === "string" ? bodyRecord.code : null;
        for (const field of ["message", "detail", "title"]) {
            if (field in bodyRecord) {
                const message = bodyRecord[field];
                if (typeof message === "string" && message.trim().length > 0) {
                    return { code, message };
                }
            }
        }
    }

    return { code: null, message: `Request failed (${status})` };
}

export const deviceApi = {
    getDiscovery: () => request<DeviceDiscoveryResponse>("/devices/discovery"),
    openDiscoveryStream: () =>
        new EventSource(`${apiBaseUrl}/devices/discovery/events`),
    discover: () =>
        request<DeviceDiscoveryResponse>("/devices/discovery", {
            method: "POST",
        }),
    getConnection: () =>
        request<DeviceConnectionsResponse>("/devices/connections"),
    connect: (deviceId: string) =>
        request<DeviceConnectionsResponse>(
            `/devices/${encodeURIComponent(deviceId)}/connection`,
            { method: "POST" },
        ),
    disconnect: (connectionId: string) =>
        request<void>(
            `/devices/connections/${encodeURIComponent(connectionId)}`,
            { method: "DELETE" },
        ),
};

export const trainingApi = {
    getCurrent: () =>
        request<TrainingSessionResponse>("/training-sessions/current"),
    getEquipment: () =>
        request<RideEquipmentResponse>("/training-sessions/equipment"),
    start: (
        workout?: WorkoutSelection,
        equipment?: RideEquipmentSelection | null,
    ) => {
        const requestBody = {
            ...(workout ? { workout } : {}),
            ...(equipment ? { equipment } : {}),
        };
        const hasBody = Object.keys(requestBody).length > 0;

        return request<TrainingSessionResponse>("/training-sessions", {
            method: "POST",
            ...(hasBody
                ? {
                      headers: { "Content-Type": "application/json" },
                      body: JSON.stringify(requestBody),
                  }
                : {}),
        });
    },
    selectEquipment: (selection: Partial<RideEquipmentSelection>) =>
        request<RideEquipmentResponse>("/training-sessions/equipment", {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(selection),
        }),
    selectEquipmentRole: (role: RideRole, sourceId: string) =>
        request<RideEquipmentResponse>(
            `/training-sessions/equipment/${encodeURIComponent(role)}`,
            {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ sourceId }),
            },
        ),
    clearEquipmentRole: (role: RideRole) =>
        request<RideEquipmentResponse>(
            `/training-sessions/equipment/${encodeURIComponent(role)}`,
            { method: "DELETE" },
        ),
    setErgTarget: (sessionId: string, powerWatts: number) =>
        request<TrainingSessionResponse>(
            `/training-sessions/${encodeURIComponent(sessionId)}/erg-target`,
            {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ powerWatts }),
            },
        ),
    adjustWorkoutTarget: (sessionId: string, deltaPercent: number) =>
        request<TrainingSessionResponse>(
            `/training-sessions/${encodeURIComponent(sessionId)}/workout-target-adjustment`,
            {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ deltaPercent }),
            },
        ),
    pause: (sessionId: string) =>
        request<TrainingSessionResponse>(
            `/training-sessions/${encodeURIComponent(sessionId)}/pause`,
            { method: "POST" },
        ),
    resume: (sessionId: string) =>
        request<TrainingSessionResponse>(
            `/training-sessions/${encodeURIComponent(sessionId)}/resume`,
            { method: "POST" },
        ),
    stop: (sessionId: string) =>
        request<TrainingSessionResponse>(
            `/training-sessions/${encodeURIComponent(sessionId)}/stop`,
            { method: "POST" },
        ),
    upload: (sessionId: string) =>
        request<TrainingSessionResponse>(
            `/training-sessions/${encodeURIComponent(sessionId)}/upload`,
            { method: "POST" },
        ),
    discard: (sessionId: string) =>
        request<TrainingSessionResponse>(
            `/training-sessions/${encodeURIComponent(sessionId)}/discard`,
            { method: "POST" },
        ),
    advance: (sessionId: string) =>
        request<TrainingSessionResponse>(
            `/training-sessions/${encodeURIComponent(sessionId)}/advance`,
            { method: "POST" },
        ),
};

export const workoutApi = {
    getToday: () => request<TodayWorkoutsResponse>("/workouts/today"),
    getLibrary: () => request<WorkoutLibraryResponse>("/workouts/library"),
};

export const profileApi = {
    get: () => request<AthleteProfile>("/profile"),
};
