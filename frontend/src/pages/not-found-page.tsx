import { ArrowLeft } from "lucide-react";
import { useNavigate } from "react-router";
import { PacelineMark } from "@/components/paceline-mark";

export function NotFoundPage() {
    const navigate = useNavigate();

    return (
        <div className="mx-auto flex min-h-[70vh] max-w-xl flex-col items-center justify-center px-5 text-center">
            <span className="grid size-16 place-items-center rounded-[1.05rem] bg-[#080d19] shadow-[0_15px_35px_rgba(139,104,255,0.2)]">
                <PacelineMark className="size-full" />
            </span>
            <p className="mt-7 text-[0.62rem] font-bold tracking-[0.2em] text-[#8f98ad] uppercase">
                Wrong turn
            </p>
            <h1 className="mt-3 text-4xl font-black tracking-[-0.08em] text-white">
                This ride does not exist.
            </h1>
            <button
                type="button"
                onClick={() => navigate("/")}
                className="mt-8 inline-flex min-h-12 items-center gap-2 rounded-2xl bg-[#7e87ff] px-5 text-sm font-black text-[#0b0d14] transition-colors hover:bg-[#aeb4ff] focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
            >
                <ArrowLeft aria-hidden="true" className="size-4" />
                Back to today
            </button>
        </div>
    );
}
