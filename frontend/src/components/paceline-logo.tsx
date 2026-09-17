import { PacelineMark } from "@/components/paceline-mark";

export function PacelineLogo({ onClick }: { onClick: () => void }) {
    return (
        <button
            type="button"
            onClick={onClick}
            className="group inline-flex shrink-0 items-center gap-3 rounded-2xl text-left focus-visible:ring-2 focus-visible:ring-[#8b92ff] focus-visible:outline-none"
        >
            <span className="grid size-10 place-items-center rounded-[0.65rem] bg-[#080d19] shadow-[0_8px_25px_rgba(139,104,255,0.22)] transition-transform duration-200 group-hover:-rotate-6">
                <PacelineMark className="size-full" />
            </span>
            <span>
                <span className="block text-[1.05rem] font-black tracking-[-0.06em] text-[#f5f6fb]">
                    paceline
                </span>
            </span>
        </button>
    );
}
