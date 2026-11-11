package com.codepress.app.data

/** Turns the connectors the user filled in (Settings → Connectors) into a section of the AI's system prompt. */
object Connectors {

    private val projectIdRegex = Regex("""["']?(?:project_id|projectId)["']?\s*[:=]\s*["']([^"']+)["']""")

    /** Project id found in a pasted Firebase config, or null. */
    fun firebaseProjectId(config: String): String? = projectIdRegex.find(config)?.groupValues?.get(1)

    /** Accepts "abc.supabase.co", "https://abc.supabase.co/" or the REST URL, and returns "https://abc.supabase.co". */
    fun normalizeSupabaseUrl(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return u.removeSuffix("/rest/v1").removeSuffix("/auth/v1").trimEnd('/')
    }

    fun prompt(s: SecureSettings): String {
        val firebase = s.firebaseConfig
        val supaUrl = s.supabaseUrl
        val supaKey = s.supabaseAnonKey
        val imgbb = s.imgbbKey
        if (firebase.isBlank() && (supaUrl.isBlank() || supaKey.isBlank()) && imgbb.isBlank()) return ""
        return buildString {
            append("\n\nConnected services. The user added these credentials in Settings → Connectors on purpose, so you can use them in the apps you build. Use them directly and do not ask for them again.\n")
            if (firebase.isNotBlank()) {
                append("\n- Firebase. The user pasted this Firebase config (")
                append(firebaseProjectId(firebase)?.let { "project id: $it" } ?: "project id not found in the text")
                append("):\n```\n").append(firebase.take(6000)).append("\n```\n")
                append("  For a web app use the Firebase JS SDK with these values (Authentication, Firestore, Storage). ")
                append("For an Android app, if the text is a google-services.json, write it to app/google-services.json and apply the com.google.gms.google-services plugin; otherwise use the values with FirebaseOptions.Builder. ")
                append("Remind the user to set secure Firestore/Storage rules.\n")
            }
            if (supaUrl.isNotBlank() && supaKey.isNotBlank()) {
                append("\n- Supabase. Project URL: ").append(supaUrl).append("\n  Anon (public) key: ").append(supaKey).append("\n")
                append("  Use supabase-js (createClient(url, anonKey)) or the REST API at <url>/rest/v1/<table> with the headers `apikey: <anon key>` and `Authorization: Bearer <anon key>`. ")
                append("Only the anon key is available; never ask for or use a service_role key in app code. Remind the user to enable Row Level Security on their tables.\n")
            }
            if (imgbb.isNotBlank()) {
                append("\n- ImgBB (image hosting). API key: ").append(imgbb).append("\n")
                append("  Upload with POST https://api.imgbb.com/1/upload?key=<key> as multipart/form-data, field `image` = a file, a base64 string or an image URL (optional `name`, `expiration` in seconds). ")
                append("The JSON answer has data.url (direct link), data.display_url and data.delete_url. ")
                append("A key placed in client-side code can be read by anyone who gets the app, so tell the user to keep such an app private or move the upload to a small backend.\n")
            }
        }
    }
}
