import {
    ArrowLeft,
    Check,
    Clock3,
    LoaderCircle,
    Send,
    Square,
    Upload,
    X,
    Zap,
} from "lucide-react";
import { useEffect, type ReactNode } from "react";
import { formatDurationSeconds } from "@/lib/connection";
import type {
    TrainingActivitySummary,
    TrainingActivityUploadResponse,
} from "@/types";

export function StopPrompt({
    isStopping,
    onCancel,
    onConfirm,
}: {
    isStopping: boolean;
    onCancel: () => void;
    onConfirm: () => void;
}) {
    return (
        <ModalBackdrop onClose={onCancel}>
            <div className="w-full max-w-md rounded-[2rem] border border-white/[0.1] bg-[#151a24] p-5 text-white shadow-[0_28px_80px_rgba(0,0,0,0.5)] sm:p-7">
                <div className="flex items-start justify-between gap-4">
                    <div>
                        <p className="text-[0.6rem] font-bold tracking-[0.2em] text-[#ff8068] uppercase">
                            End ride
                        </p>
                        <h2 className="mt-3 text-3xl font-black tracking-[-0.08em]">
                            Stop this ride?
                        </h2>
                    </div>
                    <button
                        type="button"
                        onClick={onCancel}
                        aria-label="Keep riding"
                        className="grid size-9 place-items-center rounded-xl text-white/35 hover:bg-white/[0.08] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
                    >
                        <X aria-hidden="true" className="size-5" />
                    </button>
                </div>
                <p className="mt-5 text-sm leading-6 text-white/48">
                    Paceline will send a 0 W target to the trainer, finish the
                    local recording, and open the Intervals.icu upload option.
                </p>
                <div className="mt-7 flex flex-col-reverse gap-2.5 sm:flex-row sm:justify-end">
                    <button
                        type="button"
                        onClick={onCancel}
                        className="min-h-11 rounded-xl px-4 text-sm font-bold text-white/55 transition-colors hover:bg-white/[0.07] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
                    >
                        Keep riding
                    </button>
                    <button
                        type="button"
                        onClick={onConfirm}
                        disabled={isStopping}
                        className="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl bg-[#ff8068] px-4 text-sm font-black text-[#210b09] transition-colors hover:bg-[#ff9b88] focus-visible:ring-2 focus-visible:ring-[#ff8068] focus-visible:outline-none disabled:opacity-60"
                    >
                        {isStopping ? (
                            <LoaderCircle
                                aria-hidden="true"
                                className="size-4 animate-spin"
                            />
                        ) : (
                            <Square
                                aria-hidden="true"
                                className="size-3.5 fill-current"
                            />
                        )}
                        {isStopping ? "Stopping…" : "Stop ride"}
                    </button>
                </div>
            </div>
        </ModalBackdrop>
    );
}

export function PostRideSheet({
    summary,
    upload,
    isUploading,
    isDiscarding,
    error,
    onDiscard,
    onUpload,
}: {
    summary: TrainingActivitySummary | null;
    upload: TrainingActivityUploadResponse;
    isUploading: boolean;
    isDiscarding: boolean;
    error: string | null;
    onDiscard: () => void;
    onUpload: () => void;
}) {
    const canUpload = upload.state === "AVAILABLE" || upload.state === "FAILED";

    return (
        <ModalBackdrop>
            <div className="w-full max-w-lg rounded-[2rem] border border-white/[0.1] bg-[#151a24] p-5 text-white shadow-[0_28px_80px_rgba(0,0,0,0.5)] sm:p-7">
                <div className="flex items-start justify-between gap-4">
                    <div>
                        <span className="grid size-11 place-items-center rounded-2xl bg-[#73d6a1] text-[#0b1912]">
                            <Check aria-hidden="true" className="size-5" />
                        </span>
                        <p className="mt-5 text-[0.6rem] font-bold tracking-[0.2em] text-[#73d6a1] uppercase">
                            Ride ended
                        </p>
                        <h2 className="mt-2 text-3xl font-black tracking-[-0.08em]">
                            Nice work.
                        </h2>
                    </div>
                </div>

                <div className="mt-6 grid grid-cols-2 gap-2.5">
                    <SummaryStat
                        label="Duration"
                        value={formatDurationSeconds(
                            summary?.durationSeconds ?? null,
                        )}
                        icon={Clock3}
                    />
                    <SummaryStat
                        label="Average power"
                        value={
                            summary?.averagePowerWatts === null ||
                            summary === null
                                ? "—"
                                : `${summary.averagePowerWatts} W`
                        }
                        icon={Zap}
                    />
                </div>

                <div className="mt-5 rounded-2xl border border-white/[0.08] bg-white/[0.04] p-4">
                    <div className="flex items-start gap-3">
                        <Upload
                            aria-hidden="true"
                            className="mt-0.5 size-5 text-[#aeb4ff]"
                        />
                        <div>
                            <p className="text-sm font-bold">
                                Send this activity to Intervals.icu?
                            </p>
                            <p className="mt-1 text-xs leading-5 text-white/42">
                                Upload it now or discard this local activity.
                                Intervals.icu remains your training history.
                            </p>
                        </div>
                    </div>
                    {upload.error ? (
                        <p className="mt-3 text-xs leading-5 text-[#ffaaa1]">
                            {upload.error}
                        </p>
                    ) : null}
                    {error ? (
                        <p className="mt-3 text-xs leading-5 text-[#ffaaa1]">
                            {error}
                        </p>
                    ) : null}
                </div>

                <div className="mt-6 flex flex-col gap-2.5">
                    {canUpload ? (
                        <button
                            type="button"
                            onClick={onUpload}
                            disabled={isUploading || isDiscarding}
                            className="inline-flex min-h-13 items-center justify-center gap-2 rounded-2xl bg-[#7e87ff] px-5 text-sm font-black text-[#0b0d14] transition-colors hover:bg-[#aeb4ff] focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none disabled:opacity-60"
                        >
                            {isUploading ? (
                                <LoaderCircle
                                    aria-hidden="true"
                                    className="size-4 animate-spin"
                                />
                            ) : (
                                <Send aria-hidden="true" className="size-4" />
                            )}
                            {isUploading
                                ? "Sending activity…"
                                : "Send to Intervals.icu"}
                        </button>
                    ) : null}
                    <button
                        type="button"
                        onClick={onDiscard}
                        disabled={isUploading || isDiscarding}
                        className="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl px-5 text-sm font-bold text-white/50 transition-colors hover:bg-white/[0.07] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50"
                    >
                        {isDiscarding ? "Discarding…" : "Discard"}
                        <ArrowLeft
                            aria-hidden="true"
                            className="size-4 rotate-180"
                        />
                    </button>
                </div>
            </div>
        </ModalBackdrop>
    );
}

function SummaryStat({
    label,
    value,
    icon: Icon,
}: {
    label: string;
    value: string;
    icon: typeof Clock3;
}) {
    return (
        <div className="rounded-2xl border border-white/[0.08] bg-white/[0.04] p-3.5">
            <Icon aria-hidden="true" className="size-4 text-white/35" />
            <p className="mt-3 text-[0.58rem] font-bold tracking-[0.14em] text-white/30 uppercase">
                {label}
            </p>
            <p className="mt-1 text-xl font-black tracking-[-0.05em] text-white">
                {value}
            </p>
        </div>
    );
}

function ModalBackdrop({
    children,
    onClose,
}: {
    children: ReactNode;
    onClose?: () => void;
}) {
    useEffect(() => {
        const handleKeyDown = (event: KeyboardEvent) => {
            if (event.key === "Escape") {
                onClose?.();
            }
        };
        const previousOverflow = document.body.style.overflow;
        document.body.style.overflow = "hidden";
        window.addEventListener("keydown", handleKeyDown);
        return () => {
            document.body.style.overflow = previousOverflow;
            window.removeEventListener("keydown", handleKeyDown);
        };
    }, [onClose]);

    return (
        <div
            className="fixed inset-0 z-[95] flex items-center justify-center bg-[#05070c]/78 p-4 backdrop-blur-sm sm:p-6"
            role="presentation"
            onMouseDown={(event) => {
                if (event.target === event.currentTarget) {
                    onClose?.();
                }
            }}
        >
            {children}
        </div>
    );
}
