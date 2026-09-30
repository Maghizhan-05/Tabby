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
    private let supabaseURL: URL?
    private let anonKey: String?

    #if canImport(Supabase)
    private(set) var client: SupabaseClient?
    #endif

    private init() {
        let urlString = (Bundle.main.object(forInfoDictionaryKey: "SUPABASE_URL") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let key = (Bundle.main.object(forInfoDictionaryKey: "SUPABASE_ANON_KEY") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)

        // xcconfig substitution can leave placeholder or empty strings.
        let cleanedURL = (urlString?.isEmpty == false && urlString != "$(SUPABASE_URL)") ? urlString : nil
        let cleanedKey = (key?.isEmpty == false && key != "$(SUPABASE_ANON_KEY)") ? key : nil

        if let cleanedURL, let url = URL(string: cleanedURL), let cleanedKey {
            self.supabaseURL = url
            self.anonKey = cleanedKey
            self.isConfigured = true
            #if canImport(Supabase)
            self.client = SupabaseClient(supabaseURL: url, supabaseKey: cleanedKey)
            #endif
        } else {
            self.supabaseURL = nil
            self.anonKey = nil
            self.isConfigured = false
        }
    }
}
