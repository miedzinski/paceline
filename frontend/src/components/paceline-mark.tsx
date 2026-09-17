import type { SVGProps } from "react";
import { useId } from "react";

export function PacelineMark({ className, ...props }: SVGProps<SVGSVGElement>) {
    const gradientId = `paceline-mark-gradient-${useId().replaceAll(":", "")}`;

    return (
        <svg
            aria-hidden="true"
            className={className}
            fill="none"
            focusable="false"
            viewBox="0 0 24 24"
            {...props}
        >
            <defs>
                <linearGradient
                    id={gradientId}
                    gradientUnits="userSpaceOnUse"
                    x1="4"
                    y1="20"
                    x2="20"
                    y2="4"
                >
                    <stop offset="0" stopColor="#8ca8ff" />
                    <stop offset="0.52" stopColor="#8b68ff" />
                    <stop offset="1" stopColor="#d184ff" />
                </linearGradient>
            </defs>
            <g
                transform="translate(12 12) scale(0.7) translate(-12 -12)"
                stroke={`url(#${gradientId})`}
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth="1.5"
            >
                <circle cx="18.5" cy="17.5" r="3.5" />
                <circle cx="5.5" cy="17.5" r="3.5" />
                <circle cx="15" cy="5" r="1" />
                <path d="M12 17.5V14l-3-3 4-3 2 3h2" />
            </g>
        </svg>
    );
}
