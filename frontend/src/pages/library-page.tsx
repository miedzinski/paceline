import {
    CircleAlert,
    LibraryBig,
    Search,
    SlidersHorizontal,
    X,
} from "lucide-react";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router";
import { WorkoutCard } from "@/components/workout-card";
import { WorkoutDetailSheet } from "@/components/workout-detail-sheet";
import { useAppShell } from "@/lib/app-shell";
import type { WorkoutItem } from "@/lib/workouts";
import { cn } from "@/lib/utils";

export function LibraryPage() {
    const navigate = useNavigate();
    const {
        profile,
        library,
        isLibraryLoading: isLoading,
        libraryError: error,
        refreshLibrary,
    } = useAppShell();
    const [query, setQuery] = useState("");
    const [filter, setFilter] = useState("All");
    const [selectedWorkout, setSelectedWorkout] = useState<WorkoutItem | null>(
        null,
    );

    const filters = useMemo(() => {
        const values = library
            .map((workout) => workout.type?.trim())
            .filter((value): value is string => Boolean(value));
        return ["All", ...Array.from(new Set(values)).slice(0, 4)];
    }, [library]);

    const filteredWorkouts = useMemo(() => {
        const normalizedQuery = query.trim().toLowerCase();
        return library.filter((workout) => {
            const matchesFilter =
                filter === "All" || workout.type?.trim() === filter;
            const matchesQuery =
                normalizedQuery.length === 0 ||
                [
                    workout.name,
                    workout.description,
                    workout.type,
                    workout.target,
                ]
                    .filter((value): value is string => Boolean(value))
                    .some((value) =>
                        value.toLowerCase().includes(normalizedQuery),
                    );
            return matchesFilter && matchesQuery;
        });
    }, [filter, library, query]);

    const startWorkout = (selection: {
        provider: string;
        sourceType: "SCHEDULED" | "LIBRARY";
        sourceId: string;
    }) => {
        const workout = selectedWorkout;
        navigate("/ride", {
            state: {
                workoutSelection: selection,
                workout,
            },
        });
        setSelectedWorkout(null);
    };

    return (
        <div className="mx-auto max-w-[1200px] px-4 pt-8 pb-12 sm:px-7 sm:pt-12 lg:px-12 lg:pt-14">
            <section className="flex flex-col justify-between gap-8 xl:flex-row xl:items-end">
                <div className="max-w-3xl">
                    <h1 className="flex items-center gap-2 text-[0.62rem] font-bold tracking-[0.22em] text-[#8f98ad] uppercase">
                        <LibraryBig aria-hidden="true" className="size-3.5" />
                        Workout library
                    </h1>
                </div>
                <div className="flex items-center gap-2 text-xs font-bold text-white/35">
                    <SlidersHorizontal aria-hidden="true" className="size-4" />
                    {library.length} saved{" "}
                    {library.length === 1 ? "workout" : "workouts"}
                </div>
            </section>

            <div className="mt-9 flex flex-col gap-3 lg:flex-row lg:items-center">
                <label className="flex min-h-14 min-w-0 flex-1 items-center gap-3 rounded-2xl border border-white/[0.1] bg-[#141821] px-4 transition focus-within:border-[#7e87ff]/70 focus-within:ring-2 focus-within:ring-[#7e87ff]/15">
                    <Search
                        aria-hidden="true"
                        className="size-5 text-white/35"
                    />
                    <span className="sr-only">Search your workout library</span>
                    <input
                        type="search"
                        value={query}
                        onChange={(event) => setQuery(event.target.value)}
                        placeholder="Search by workout name, type, or target"
                        className="min-w-0 flex-1 bg-transparent text-sm font-semibold text-white outline-none placeholder:text-white/25"
                    />
                    {query ? (
                        <button
                            type="button"
                            onClick={() => setQuery("")}
                            aria-label="Clear search"
                            className="grid size-8 place-items-center rounded-xl text-white/35 transition hover:bg-white/[0.08] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
                        >
                            <X aria-hidden="true" className="size-4" />
                        </button>
                    ) : null}
                </label>
                <div className="flex gap-2 overflow-x-auto pb-1 lg:pb-0">
                    {filters.map((item) => (
                        <button
                            key={item}
                            type="button"
                            onClick={() => setFilter(item)}
                            className={cn(
                                "min-h-11 shrink-0 rounded-2xl border px-4 text-xs font-bold transition-colors focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none",
                                filter === item
                                    ? "border-[#7e87ff] bg-[#7e87ff] text-[#0b0d14]"
                                    : "border-white/[0.1] bg-white/[0.035] text-white/45 hover:border-white/[0.2] hover:text-white",
                            )}
                        >
                            {item}
                        </button>
                    ))}
                </div>
            </div>

            {error ? (
                <div className="mt-6 flex items-start gap-3 rounded-[1.65rem] border border-[#703e49] bg-[#2a1821] p-5 text-sm leading-6 text-[#ffc4c5]">
                    <CircleAlert
                        aria-hidden="true"
                        className="mt-0.5 size-5 shrink-0"
                    />
                    <div>
                        <p className="font-bold">Could not load the library</p>
                        <p className="mt-1 text-white/60">{error}</p>
                        <button
                            type="button"
                            onClick={() => void refreshLibrary()}
                            className="mt-3 font-bold text-[#ffaaa1] underline underline-offset-4"
                        >
                            Try again
                        </button>
                    </div>
                </div>
            ) : isLoading ? (
                <div className="mt-8 grid gap-4 md:grid-cols-2 xl:grid-cols-3">
                    {Array.from({ length: 6 }, (_, index) => (
                        <div
                            key={index}
                            className="min-h-[19rem] animate-pulse rounded-[2rem] border border-white/[0.08] bg-white/[0.035]"
                        />
                    ))}
                </div>
            ) : filteredWorkouts.length > 0 ? (
                <div className="mt-8 grid gap-4 md:grid-cols-2 xl:grid-cols-3">
                    {filteredWorkouts.map((workout) => (
                        <WorkoutCard
                            key={`${workout.provider}-${workout.sourceWorkoutId}`}
                            workout={workout}
                            onSelect={setSelectedWorkout}
                            athleteProfile={profile}
                        />
                    ))}
                </div>
            ) : (
                <div className="mt-8 rounded-[2rem] border border-dashed border-white/[0.16] bg-white/[0.025] px-6 py-16 text-center">
                    <LibraryBig
                        aria-hidden="true"
                        className="mx-auto size-8 text-white/25"
                    />
                    <p className="mt-4 text-xl font-black tracking-[-0.05em] text-white">
                        Nothing matches that search.
                    </p>
                    <p className="mt-2 text-sm text-white/40">
                        Try another name, type, or target.
                    </p>
                </div>
            )}

            <WorkoutDetailSheet
                workout={selectedWorkout}
                athleteProfile={profile}
                onClose={() => setSelectedWorkout(null)}
                onStart={startWorkout}
            />
        </div>
    );
}
