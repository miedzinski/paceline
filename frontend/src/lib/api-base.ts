export function resolveApiBaseUrl(
    isProduction: boolean,
    configuredValue: string | undefined,
): string {
    if (isProduction) {
        return "";
    }

    return (configuredValue?.trim() || "/api").replace(/\/$/, "");
}
