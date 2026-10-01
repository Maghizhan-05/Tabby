import Foundation

/// Assembles a *provably complete* list from a paged source.
///
/// Absence-based deletion in `ExpenseReconciliation` is only sound against a
/// complete snapshot, and Supabase caps an unpaged `select` (1000 rows by
/// default). This helper owns the termination rule so it can be tested without
/// a network: keep requesting ordered ranges until a page comes back shorter
/// than `pageSize`, which is the only proof the end was reached. Any error from
/// `fetchPage` propagates so the caller aborts instead of acting on a partial
/// result.
enum PaginatedSnapshot {

    /// Hard ceiling on requests, so a backend that keeps returning full pages
    /// (misconfigured range handling) fails loudly instead of looping forever.
    static let maximumPages = 200

    struct IncompleteSnapshot: LocalizedError {
        let pagesFetched: Int
        var errorDescription: String? {
            "Remote snapshot did not terminate after \(pagesFetched) pages."
        }
    }

    /// - Parameters:
    ///   - pageSize: rows requested per page; must be > 0.
    ///   - fetchPage: called with the inclusive `(from, to)` range of each page.
    static func fetchAll<Element>(
        pageSize: Int,
        fetchPage: (_ from: Int, _ to: Int) async throws -> [Element]
    ) async throws -> [Element] {
        guard pageSize > 0 else { return [] }

        var all: [Element] = []
        var offset = 0

        for _ in 0..<maximumPages {
            let page = try await fetchPage(offset, offset + pageSize - 1)
            all.append(contentsOf: page)
            // A short (or empty) page proves completeness.
            if page.count < pageSize { return all }
            offset += pageSize
        }

        throw IncompleteSnapshot(pagesFetched: maximumPages)
    }
}
