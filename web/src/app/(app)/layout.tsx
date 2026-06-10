import { NavBar } from "@/components/nav-bar";

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="mx-auto flex min-h-screen max-w-5xl flex-col">
      <main className="flex-1 px-4 pb-24 pt-6 sm:px-6">{children}</main>
      <NavBar />
    </div>
  );
}
