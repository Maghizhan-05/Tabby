import XCTest
@testable import SpendTracker

/// Verifies DEFECT A: the xcconfig "//" comment problem and NEXT_PUBLIC_* → SUPABASE_* bridging.
final class SupabaseConfigTests: XCTestCase {

    func testRepairURLHandlesWellFormedURL() {
        XCTAssertEqual(
            SupabaseClientProvider.repairURLString("https://hhbrzfkgiszlspygifgf.supabase.co"),
            "https://hhbrzfkgiszlspygifgf.supabase.co"
        )
    }

    func testRepairURLRestoresSingleStrippedSlash() {
        // xcconfig can leave "https:/host" after eating one of the "//".
        XCTAssertEqual(
            SupabaseClientProvider.repairURLString("https:/hhbrzfkgiszlspygifgf.supabase.co"),
            "https://hhbrzfkgiszlspygifgf.supabase.co"
        )
    }

    func testRepairURLRestoresBothStrippedSlashes() {
        // "https:host" — both slashes eaten but host survived (escaped value).
        XCTAssertEqual(
            SupabaseClientProvider.repairURLString("https:hhbrzfkgiszlspygifgf.supabase.co"),
            "https://hhbrzfkgiszlspygifgf.supabase.co"
        )
    }

    func testRepairURLAddsSchemeWhenMissing() {
        XCTAssertEqual(
            SupabaseClientProvider.repairURLString("hhbrzfkgiszlspygifgf.supabase.co"),
            "https://hhbrzfkgiszlspygifgf.supabase.co"
        )
    }

    func testRepairURLReturnsNilForLostHost() {
        // "https:" with the host eaten by the comment rule is unrecoverable.
        XCTAssertNil(SupabaseClientProvider.repairURLString("https:"))
        XCTAssertNil(SupabaseClientProvider.repairURLString(""))
        XCTAssertNil(SupabaseClientProvider.repairURLString("$(SUPABASE_URL)"))
        XCTAssertNil(SupabaseClientProvider.repairURLString("$(NEXT_PUBLIC_SUPABASE_URL)"))
    }

    /// When the app bundle carries live keys (Config/Secrets.xcconfig present),
    /// the provider must report configured. In CI without keys this is skipped.
    func testProviderReadsLiveKeysWhenPresent() throws {
        let url = Bundle.main.object(forInfoDictionaryKey: "SUPABASE_URL") as? String
        let key = (Bundle.main.object(forInfoDictionaryKey: "SUPABASE_PUBLISHABLE_KEY") as? String)
            ?? (Bundle.main.object(forInfoDictionaryKey: "SUPABASE_ANON_KEY") as? String)

        let hasURL = SupabaseClientProvider.repairURLString(url) != nil
        let hasKey = (key?.isEmpty == false) && key != "$(SUPABASE_PUBLISHABLE_KEY)"

        try XCTSkipUnless(hasURL && hasKey, "No live Supabase keys in this build; skipping.")
        XCTAssertTrue(SupabaseClientProvider.shared.isConfigured,
                      "Live keys present but provider not configured — DEFECT A regression.")
    }
}
