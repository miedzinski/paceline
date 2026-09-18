import type { AthleteProfile } from "@/types";

export const minimumFtmsPowerWatts = -32_768;
export const maximumFtmsPowerWatts = 32_767;

export function initialManualErgTargetWatts(
    profile: AthleteProfile | null | undefined,
): number | null {
    const ftpWatts = profile?.indoorFtpWatts ?? profile?.ftpWatts;
    if (ftpWatts === null || ftpWatts === undefined || ftpWatts <= 0) {
        return null;
    }

    return Math.floor(ftpWatts * 0.5);
}

export function adjustManualErgTargetWatts(
    currentWatts: number,
    deltaWatts: number,
): number {
    return Math.min(
        maximumFtmsPowerWatts,
        Math.max(minimumFtmsPowerWatts, currentWatts + deltaWatts),
    );
}
