import Foundation
import SwiftData

@MainActor
final class FriendsViewModel: ObservableObject {
    @Published var name = ""
    @Published var theyOweUsText = ""
    @Published var weOweThemText = ""
    @Published var editingFriend: Friend?
    @Published var isPresentingEditor = false
    @Published var notice: String?

    var canSave: Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        balance(from: theyOweUsText) != nil &&
        balance(from: weOweThemText) != nil
    }

    /// Rows the signed-in user may see. Records owned by another account are
    /// never surfaced, and nothing is shown while signed out.
    /// Owner comparison is normalized (case/whitespace-insensitive): a locally
    /// created row is stamped with the session's uppercase uuid string, while a
    /// row pulled from Supabase carries Postgres's canonical lowercase form.
    /// A raw `==` here would hide every pulled friend.
    static func visibleFriends(_ friends: [Friend], ownerId: String?) -> [Friend] {
        guard ExpenseOwnership.normalized(ownerId) != nil else { return [] }
        return friends.filter {
            $0.syncState != .deleted
                && ExpenseOwnership.isAccessible(recordOwnerId: $0.ownerId, activeOwnerId: ownerId)
        }
    }

    static func aggregateNet(of friends: [Friend]) -> Decimal {
        friends
            .filter { $0.syncState != .deleted }
            .reduce(Decimal.zero) { $0 + $1.netBalance }
    }

    func beginAdding() {
        editingFriend = nil
        name = ""
        theyOweUsText = "0"
        weOweThemText = "0"
        notice = nil
        isPresentingEditor = true
    }

    func beginEditing(_ friend: Friend) {
        editingFriend = friend
        name = friend.name
        theyOweUsText = decimalText(friend.theyOweUs)
        weOweThemText = decimalText(friend.weOweThem)
        notice = nil
        isPresentingEditor = true
    }

    /// Saves transactionally: a failed persist is rolled back and reported, and
    /// only a durable save returns true so callers never sync uncommitted state.
    @discardableResult
    func save(context: ModelContext, ownerId: String?) -> Bool {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty,
              let theyOweUs = balance(from: theyOweUsText),
              let weOweThem = balance(from: weOweThemText) else {
            notice = "Enter a name and valid balances."
            return false
        }

        guard let ownerId, !ownerId.isEmpty else {
            notice = "Sign in to save friends."
            return false
        }

        if let friend = editingFriend {
            guard ExpenseOwnership.isAccessible(
                recordOwnerId: friend.ownerId, activeOwnerId: ownerId
            ) else {
                notice = "This record belongs to another account."
                return false
            }
            friend.name = trimmedName
            friend.theyOweUs = theyOweUs
            friend.weOweThem = weOweThem
            friend.updatedAt = Date()
            if friend.syncState == .synced { friend.syncState = .dirty }
        } else {
            context.insert(
                Friend(name: trimmedName, ownerId: ownerId, theyOweUs: theyOweUs, weOweThem: weOweThem)
            )
        }

        do {
            try context.save()
            isPresentingEditor = false
            notice = nil
            return true
        } catch {
            // Discard the failed mutation so the UI never shows uncommitted state.
            context.rollback()
            notice = "Could not save. Try again."
            return false
        }
    }

    /// Marks the record deleted locally. SyncEngine retains it until the remote
    /// delete succeeds, so offline deletion is retried rather than lost.
    @discardableResult
    func delete(_ friend: Friend, context: ModelContext, ownerId: String?) -> Bool {
        guard ExpenseOwnership.isAccessible(
            recordOwnerId: friend.ownerId, activeOwnerId: ownerId
        ) else { return false }
        friend.syncState = .deleted
        friend.updatedAt = Date()
        do {
            try context.save()
            return true
        } catch {
            context.rollback()
            notice = "Could not delete. Try again."
            return false
        }
    }

    private func balance(from text: String) -> Decimal? {
        let normalized = text.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: ",", with: ".")
        guard let value = Decimal(string: normalized), value >= 0 else { return nil }
        return value
    }

    private func decimalText(_ value: Decimal) -> String {
        NSDecimalNumber(decimal: value).stringValue
    }
}
