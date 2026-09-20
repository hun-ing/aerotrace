import LoginForm from "@/features/auth/login-form";

export const dynamic = "force-dynamic";

export default async function Login({ searchParams }: { searchParams: Promise<{ error?: string }> }) {
  const { error } = await searchParams;
  return <LoginForm failed={error !== undefined} />;
}
