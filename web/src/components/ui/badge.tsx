import * as React from "react";
import { cn } from "@/lib/utils";

export function Badge({
  className,
  variant = "default",
  ...props
}: React.HTMLAttributes<HTMLDivElement> & { variant?: "default" | "outline" | "success" | "warning" | "danger" }) {
  const variants: Record<string, string> = {
    default: "bg-secondary text-secondary-foreground",
    outline: "border border-border text-foreground",
    success: "bg-primary/15 text-primary",
    warning: "bg-amber-500/15 text-amber-400",
    danger: "bg-destructive/15 text-red-400",
  };
  return (
    <div
      className={cn(
        "inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium",
        variants[variant],
        className,
      )}
      {...props}
    />
  );
}
