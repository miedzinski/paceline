import {
    ArrowUpRight,
    ChevronLeft,
    ChevronRight,
    CircleAlert,
    LibraryBig,
} from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router";
import { useAppShell } from "@/lib/app-shell";
import { WorkoutCard } from "@/components/workout-card";
import { WorkoutDetailSheet } from "@/components/workout-detail-sheet";
import type { ScheduledWorkout } from "@/types";
import type { WorkoutItem } from "@/lib/workouts";

export function TodayPage() {
    const navigate = useNavigate();
    const { profile, today, isPlanLoading, planError, refreshPlan } =
        useAppShell();
    const [selectedWorkout, setSelectedWorkout] = useState<WorkoutItem | null>(
        null,
    );

    const scheduledWorkouts = today?.scheduledWorkouts ?? [];

    return (
        <div className="mx-auto max-w-[1200px] px-4 pt-8 pb-12 sm:px-7 sm:pt-12 lg:px-12 lg:pt-14">
            <section aria-labelledby="today-plan-heading">
                <div className="flex flex-wrap items-end justify-between gap-4">
                    <div>
                        <p className="text-[0.62rem] font-bold tracking-[0.2em] text-[#8f98ad] uppercase">
                            Up next
                        </p>
                        <h2
                            id="today-plan-heading"
                            className="mt-2 text-2xl font-black tracking-[-0.06em] text-white sm:text-3xl"
                        >
                            Today&apos;s plan
                        </h2>
                    </div>
                    <div className="ml-auto flex flex-wrap items-center justify-end gap-2">
                        <span className="hidden text-xs font-bold text-white/30 sm:block">
                            {today === null
                                ? "Loading"
                                : scheduledWorkouts.length === 0
                                  ? "Clear calendar"
                                  : `${scheduledWorkouts.length} scheduled`}
                        </span>
                        <button
                            type="button"
                            onClick={() => navigate("/library")}
                            className="inline-flex min-h-10 items-center gap-2 rounded-2xl border border-white/[0.1] bg-white/[0.045] px-3 text-xs font-bold text-white/65 transition-colors hover:border-white/[0.2] hover:bg-white/[0.08] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
                        >
                            <LibraryBig aria-hidden="true" className="size-4" />
                            Browse library
                        </button>
                    </div>
                </div>

                {planError ? (
                    <div className="mt-5 flex items-start gap-3 rounded-[1.65rem] border border-[#703e49] bg-[#2a1821] p-5 text-sm leading-6 text-[#ffc4c5]">
                        <CircleAlert
                            aria-hidden="true"
                            className="mt-0.5 size-5 shrink-0"
                        />
                        <div>
                            <p className="font-bold">
                                Could not load your plan
                            </p>
                            <p className="mt-1 text-white/60">{planError}</p>
                            <button
                                type="button"
                                onClick={() => void refreshPlan()}
                                className="mt-3 font-bold text-[#ffaaa1] underline underline-offset-4"
                            >
                                Try again
                            </button>
                        </div>
                    </div>
                ) : isPlanLoading || today === null ? (
                    <PlanSkeleton />
                ) : scheduledWorkouts.length === 0 ? (
                    <EmptyToday onBrowse={() => navigate("/library")} />
                ) : (
                    <WorkoutCarousel
                        workouts={scheduledWorkouts}
                        onSelect={setSelectedWorkout}
                        athleteProfile={profile}
                    />
                )}
            </section>

            <WorkoutDetailSheet
                workout={selectedWorkout}
                athleteProfile={profile}
                onClose={() => setSelectedWorkout(null)}
                onStart={(selection) => {
                    navigate("/ride", {
                        state: {
                            workoutSelection: selection,
                            workout: selectedWorkout,
                        },
                    });
                    setSelectedWorkout(null);
                }}
            />
        </div>
    );
}

function WorkoutCarousel({
    workouts,
    onSelect,
    athleteProfile,
}: {
    workouts: ScheduledWorkout[];
    onSelect: (workout: WorkoutItem) => void;
    athleteProfile: ReturnType<typeof useAppShell>["profile"];
}) {
    const carouselRef = useRef<HTMLDivElement>(null);
    const [activeIndex, setActiveIndex] = useState(0);
    const [isScrollable, setIsScrollable] = useState(false);
    const [canGoPrevious, setCanGoPrevious] = useState(false);
    const [canGoNext, setCanGoNext] = useState(false);

    const updatePosition = useCallback(() => {
        const container = carouselRef.current;
        if (container === null) {
            return;
        }

        const slides = Array.from(container.children).filter(
            (child): child is HTMLElement => child instanceof HTMLElement,
        );
        if (slides.length === 0) {
            return;
        }

        const containerRect = container.getBoundingClientRect();
        const nearestIndex = slides.reduce((closestIndex, slide, index) => {
            const slideLeft =
                slide.getBoundingClientRect().left -
                containerRect.left +
                container.scrollLeft;
            const closestSlide = slides[closestIndex];
            const closestSlideLeft =
                closestSlide.getBoundingClientRect().left -
                containerRect.left +
                container.scrollLeft;
            return Math.abs(slideLeft - container.scrollLeft) <
                Math.abs(closestSlideLeft - container.scrollLeft)
                ? index
                : closestIndex;
        }, 0);
        const maxScrollLeft = container.scrollWidth - container.clientWidth;

        setActiveIndex(nearestIndex);
        setIsScrollable(maxScrollLeft > 1);
        setCanGoPrevious(container.scrollLeft > 1);
        setCanGoNext(container.scrollLeft < maxScrollLeft - 1);
    }, []);

    useEffect(() => {
        const container = carouselRef.current;
        if (container === null) {
            return;
        }

        container.scrollTo({ left: 0, behavior: "auto" });
        updatePosition();

        const resizeObserver = new ResizeObserver(updatePosition);
        resizeObserver.observe(container);
        return () => resizeObserver.disconnect();
    }, [updatePosition, workouts.length]);

    const scrollToIndex = useCallback((index: number) => {
        const container = carouselRef.current;
        const slide = container?.children[index];
        if (
            !(container instanceof HTMLElement) ||
            !(slide instanceof HTMLElement)
        ) {
            return;
        }

        const containerRect = container.getBoundingClientRect();
        const slideLeft =
            slide.getBoundingClientRect().left -
            containerRect.left +
            container.scrollLeft;
        container.scrollTo({ left: slideLeft, behavior: "smooth" });
    }, []);

    return (
        <div
            role="region"
            className="mt-5"
            aria-label="Today's scheduled workouts"
            aria-roledescription="carousel"
        >
            <div
                ref={carouselRef}
                onScroll={updatePosition}
                className="flex snap-x snap-mandatory [scrollbar-width:none] gap-4 overflow-x-auto overscroll-x-contain pt-1 pb-2 [&::-webkit-scrollbar]:hidden"
            >
                {workouts.map((workout, index) => (
                    <div
                        key={`${workout.provider}-${workout.sourceEventId}`}
                        role="group"
                        aria-label={`Workout ${index + 1} of ${workouts.length}`}
                        aria-roledescription="slide"
                        className="flex min-w-0 shrink-0 basis-[calc(100%-1.5rem)] snap-start sm:basis-[calc(50%-0.5rem)] lg:basis-[calc(100%-1.5rem)]"
                    >
                        <WorkoutCard
                            workout={workout}
                            compact
                            onSelect={onSelect}
                            athleteProfile={athleteProfile}
                        />
                    </div>
                ))}
            </div>

            {isScrollable ? (
                <div className="mt-3 flex items-center justify-between gap-4">
                    <span
                        className="text-xs font-bold text-white/35"
                        aria-live="polite"
                    >
                        Workout {activeIndex + 1} of {workouts.length}
                    </span>
                    <div className="flex items-center gap-2">
                        <button
                            type="button"
                            onClick={() => scrollToIndex(activeIndex - 1)}
                            disabled={!canGoPrevious}
                            aria-label="Previous workout"
                            className="grid size-9 place-items-center rounded-xl border border-white/[0.1] bg-white/[0.045] text-white/60 transition-colors hover:border-white/[0.2] hover:bg-white/[0.08] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-30"
                        >
                            <ChevronLeft
                                aria-hidden="true"
                                className="size-4"
                            />
                        </button>
                        <button
                            type="button"
                            onClick={() => scrollToIndex(activeIndex + 1)}
                            disabled={!canGoNext}
                            aria-label="Next workout"
                            className="grid size-9 place-items-center rounded-xl border border-white/[0.1] bg-white/[0.045] text-white/60 transition-colors hover:border-white/[0.2] hover:bg-white/[0.08] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-30"
                        >
                            <ChevronRight
                                aria-hidden="true"
                                className="size-4"
                            />
                        </button>
                    </div>
                </div>
            ) : null}
        </div>
    );
}

function PlanSkeleton() {
    return (
        <div className="mt-5 min-h-[17rem] animate-pulse rounded-[2rem] border border-white/[0.08] bg-white/[0.045]" />
    );
}

function EmptyToday({ onBrowse }: { onBrowse: () => void }) {
    return (
        <div className="mt-5 flex flex-col items-start justify-between gap-7 rounded-[2rem] border border-dashed border-white/[0.16] bg-white/[0.025] p-6 sm:flex-row sm:items-center sm:p-9">
            <div>
                <p className="text-2xl font-black tracking-[-0.06em] text-white">
                    A clear day.
                </p>
                <p className="mt-2 max-w-xl text-sm leading-6 text-white/45">
                    Nothing uncompleted is scheduled for today. Pick a saved
                    workout if you still want to ride.
                </p>
            </div>
            <button
                type="button"
                onClick={onBrowse}
                className="inline-flex min-h-11 shrink-0 items-center gap-2 rounded-2xl bg-[#7e87ff] px-4 text-sm font-bold text-[#0b0d14] transition hover:bg-[#aeb4ff] focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
            >
                Browse library
                <ArrowUpRight aria-hidden="true" className="size-4" />
            </button>
        </div>
    );
}
