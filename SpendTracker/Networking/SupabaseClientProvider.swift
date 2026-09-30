import Foundation

#if canImport(Supabase)
import Supabase
#endif

/// Reads Supabase configuration from Info.plist keys (backed by an xcconfig)
/// and vends a configured client. If keys are missing, `isConfigured` is false
/// and all Supabase-backed features degrade gracefully.
final class SupabaseClientProvider {
    static let shared = SupabaseClientProvider()

    let isConfigured: Bool
    let supabaseURL: URL?
    private let publishableKey: String?

    #if canImport(Supabase)
    private(set) var client: SupabaseClient?
    #endif

    private init() {
        let rawURL = (Bundle.main.object(forInfoDictionaryKey: "SUPABASE_URL") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)

        // Prefer the new "publishable key" name; fall back to the legacy anon key
        // name for backward compatibility.
        let rawKey = ((Bundle.main.object(forInfoDictionaryKey: "SUPABASE_PUBLISHABLE_KEY") as? String)
            ?? (Bundle.main.object(forInfoDictionaryKey: "SUPABASE_ANON_KEY") as? String))?
            .trimmingCharacters(in: .whitespacesAndNewlines)

        // xcconfig substitution can leave placeholder or empty strings.
        let cleanedKey = (rawKey?.isEmpty == false
            && rawKey != "$(SUPABASE_PUBLISHABLE_KEY)"
            && rawKey != "$(SUPABASE_ANON_KEY)") ? rawKey : nil

        let cleanedURL = Self.repairURLString(rawURL)

        if let cleanedURL, let url = URL(string: cleanedURL),
           url.scheme != nil, url.host != nil, let cleanedKey {
            self.supabaseURL = url
            self.publishableKey = cleanedKey
            self.isConfigured = true
            #if canImport(Supabase)
            self.client = SupabaseClient(supabaseURL: url, supabaseKey: cleanedKey)
            #endif
        } else {
            self.supabaseURL = nil
            self.publishableKey = nil
            self.isConfigured = false
        }
    }

    /// Normalizes a Supabase URL string coming from xcconfig.
    ///
    /// xcconfig treats `//` as a comment, so a raw `https://host` value can arrive
    /// as `https:host` or `https:` (slashes stripped) depending on how it was
    /// written. This repairs the common cases so the live URL is actually usable:
    /// - `https://host`      -> unchanged
    /// - `https:/host`       -> `https://host`
    /// - `https:host`        -> `https://host`
    /// - `https:` (host lost)-> nil (unrecoverable; treated as unconfigured)
    /// - `host` (no scheme)  -> `https://host`
    static func repairURLString(_ input: String?) -> String? {
        guard var value = input?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty,
              value != "$(SUPABASE_URL)",
              value != "$(NEXT_PUBLIC_SUPABASE_URL)" else {
            return nil
        }

        // Split scheme from the remainder.
        if let range = value.range(of: "://") {
            // Already well-formed (https://host); keep as-is.
            let host = String(value[range.upperBound...])
            return host.isEmpty ? nil : value
        }

        if let colon = value.firstIndex(of: ":") {
            let scheme = String(value[..<colon])
            var rest = String(value[value.index(after: colon)...])
            // Strip any leading slashes left over from a mangled "//".
            while rest.hasPrefix("/") { rest.removeFirst() }
            guard !rest.isEmpty else { return nil } // e.g. "https:" — host lost
            let normalizedScheme = scheme.isEmpty ? "https" : scheme
            return "\(normalizedScheme)://\(rest)"
        }

        // No scheme at all — assume https.
        value = value.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return value.isEmpty ? nil : "https://\(value)"
    }
}
