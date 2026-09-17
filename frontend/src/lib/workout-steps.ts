export function flattenWorkoutSteps<
    T extends { repeats: number | null; steps: T[] },
>(steps: T[]): T[] {
    return steps.flatMap((step) => {
        if (step.steps.length === 0) {
            return [step];
        }

        const repetitions = Math.max(1, step.repeats ?? 1);
        const flattenedChildren = flattenWorkoutSteps(step.steps);
        return Array.from(
            { length: repetitions },
            () => flattenedChildren,
        ).flat();
    });
}
