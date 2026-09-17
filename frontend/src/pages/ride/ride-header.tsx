import { ArrowLeft, Bluetooth } from "lucide-react";
import { cn } from "@/lib/utils";

export function RideHeader({
    eyebrow,
    title,
    trainerName,
    onBack,
    onOpenEquipment,
}: {
    eyebrow: string;
    title: string;
    trainerName: string | null;
    onBack: () => void;
    onOpenEquipment: () => void;
}) {
    return (
        <div className="flex items-center justify-between gap-3">
            <button
                type="button"
                onClick={onBack}
                aria-label="Back to today"
                className="grid size-11 shrink-0 place-items-center rounded-2xl border border-white/[0.1] bg-white/[0.045] text-white/65 transition-colors hover:bg-white/[0.09] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
            >
                <ArrowLeft aria-hidden="true" className="size-5" />
            </button>
            <div className="min-w-0 flex-1 text-center">
                <p className="flex items-center justify-center gap-2 text-[0.6rem] font-bold tracking-[0.2em] text-white/35 uppercase">
                    <span className="size-1.5 rounded-full bg-[#ff8068] shadow-[0_0_12px_rgba(255,128,104,0.75)]" />
                    {eyebrow}
                </p>
                <h1 className="mt-1 truncate text-base font-black tracking-[-0.05em] text-white sm:text-lg">
                    {title}
                </h1>
            </div>
            <button
                type="button"
                onClick={onOpenEquipment}
                aria-label={
                    trainerName
                        ? `Equipment connected: ${trainerName}`
                        : "Open equipment"
                }
                className="relative grid size-11 shrink-0 place-items-center rounded-2xl border border-white/[0.1] bg-white/[0.045] text-white/65 transition-colors hover:bg-white/[0.09] hover:text-white focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
            >
                <Bluetooth aria-hidden="true" className="size-5" />
                <span
                    className={cn(
                        "absolute right-0.5 bottom-0.5 size-2.5 rounded-full border-2 border-[#090c12]",
                        trainerName ? "bg-[#73d6a1]" : "bg-[#f0b766]",
                    )}
                />
            </button>
        </div>
    );
}
