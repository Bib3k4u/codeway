import { redirect } from "next/navigation";

/** Old sign-in URL: the home page now hosts sign-in in a dialog. */
export default function LoginRedirect() {
  redirect("/?auth=login");
}
