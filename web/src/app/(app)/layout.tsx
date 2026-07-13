import { redirect } from "next/navigation";
import { NavBar } from "@/components/nav-bar";
import { createClient } from "@/lib/supabase-server";

export default async function AppLayout({ children }: { children: React.ReactNode }) {
  // New accounts land here straight from the confirmation email; route them
  // through onboarding before showing an empty dashboard.
  const supabase = createClient();
  const { data: { user } } = await supabase.auth.getUser();
  if (user) {
    const { data: profile } = await supabase
      .from("user_profiles")
      .select("onboarding_complete")
      .eq("id", user.id)
      .maybeSingle();
    if (profile && profile.onboarding_complete !== true) redirect("/onboarding");
  }

  return (
    <div className="mx-auto flex min-h-screen max-w-5xl flex-col">
      <main className="flex-1 px-4 pb-24 pt-6 sm:px-6">{children}</main>
      <NavBar />
    </div>
  );
}
